# Backend Guide (court-service)

Reference for AI agents. Workflow, Definition of Done and commit rules live in `/CLAUDE.md`; this file covers how the backend is built. When they disagree, `CLAUDE.md` wins.

## 1. Stack

| Area | Technology | Version |
|---|---|---|
| Language / build | Java, Maven wrapper (`./mvnw`) | 21 |
| Framework | Spring Boot (Web MVC, Validation, Actuator, AspectJ) | 4.1.1 |
| Database | PostgreSQL | 17 |
| Migrations | Flyway (`flyway-database-postgresql`) | Boot-managed |
| ORM | Spring Data JPA / Hibernate, `ddl-auto: validate` | Boot-managed |
| Distributed lock | Redis + Redisson | 7.4 / 4.7.0 |
| In-memory cache | Caffeine (sold-out flag) | Boot-managed |
| API docs | springdoc-openapi (Swagger UI) | 3.1.1 |
| SQL logging | datasource-proxy | 1.11.0 |
| Boilerplate | Lombok | Boot-managed |
| Tests | JUnit 5, AssertJ, Mockito, Testcontainers | Testcontainers 2.0.5 |

## 2. Layout and dependencies

Root package `com.courtservice`, packaged by feature.

| Package | Contents | Notes |
|---|---|---|
| `court/`, `member/`, `session/` | `Xxx` entity, `XxxRepository`, `XxxService`, `XxxController` flat in the package; `dto/` for request/response records | Default layout for new modules |
| `booking/` | `controller/`, `service/`, `repository/`, `entity/`, `dto/`, `config/` (`BookingProperties`), `cache/` (sold-out flag), `lock/` (lock keys, actuator endpoint), `strategy/` + `strategy/impl/` | Layered because of the strategy family; keep new booking code in the matching subpackage |
| `common/` | `BaseEntity`, `error/`, `web/` (paging, `TraceIdFilter`), `logging/`, `config/` (OpenAPI, Redisson), `health/` | Shared infrastructure only, no business rules |
| `src/main/resources/db/migration` | Flyway scripts | Schema source of truth |

Dependency rules:
- Controller → Service → Repository. Controllers never touch repositories or entities.
- Cross-module calls go through the owning module's **Service** (e.g. booking → `CourtSessionService`, `MemberService`), never another module's Repository. Repositories are `public` only for Java visibility.
- `booking` depends on `session` and `member`; nothing depends on `booking`. Reverse notifications use events (`CourtSessionChangedEvent`, handled `AFTER_COMMIT`).
- Booking writes go through `BookingStrategyRegistry.active()` (`app.booking.concurrency-mode`); each strategy owns its transaction (`BookingService#create` is `NOT_SUPPORTED`).

## 3. Database and ORM

| Topic | Rule |
|---|---|
| Schema owner | Flyway; Hibernate only validates (`ddl-auto: validate`) |
| New change | Add `V<n>__<snake_case_description>.sql`; never edit a migration merged into `develop` |
| Running migrations | Automatic on application start and in every Spring test |
| Tables / columns | Singular `snake_case` (`court_session`, `booked_count`) |
| Constraints | `chk_<table>_<rule>`, `uq_<table>_<cols>`, `idx_<table>_<col>`; FKs inline `REFERENCES` |
| Entities | Extend `BaseEntity` (`id` IDENTITY, `createdAt`, `updatedAt`); `@Getter` + `@NoArgsConstructor(PROTECTED)` only; no setters, no `@Data/@ToString/@EqualsAndHashCode/@Value`; `@ManyToOne(fetch = LAZY)` |
| Invariants | Enforced twice: entity methods (`CourtSession.book()`, `changeCapacity()`) and DB constraints (`chk_session_booked`, `uq_booking_session_member`) |
| Queries | Derived methods or JPQL. Native SQL only when JPQL cannot express it (current single case: `UPDATE ... RETURNING` in `CourtSessionRepository`); document why |
| Bulk writes | `@Modifying` JPQL, not derived `deleteBy`/`updateBy` |
| N+1 | `@EntityGraph` or DTO projections for list queries; read `getId()` from lazy proxies; `getReferenceById` to set FKs |
| Transactions | Services: class-level `@Transactional(readOnly = true)`, writes annotated `@Transactional`; map to DTOs inside the transaction; no external calls inside |
| `booked_count` | Changed only by booking strategies, never by CRUD |

## 4. API and errors

### Conventions
- Plural kebab-case resources, nested sub-resources: `/api/courts`, `/api/members`, `/api/court-sessions`, `/api/court-sessions/{id}/bookings`, `/api/members/{id}/bookings`, `/api/bookings`.
- Bodies are records in `dto/`; responses return the resource directly (no envelope); entities never leave the service.
- Lists: optional `page`, `size` (default 20, max 100), `sort` (default `id`); response `PageResponse { content, page { number, size, totalElements, totalPages } }`.
- Every endpoint documents all status codes with `@Operation`/`@ApiResponses`; error responses reference the shared Problem Details schema; every DTO field has `@Schema` with description and example.

| Operation | Success |
|---|---|
| `GET` | 200 |
| `POST` (create) | 201 + `Location` header |
| `PUT` (replace) | 200 |
| `DELETE` | 204, no body |

### Error format (RFC 9457, `application/problem+json`)
```json
{ "type": "https://court-service.example/problems/session-full", "title": "Session Full",
  "status": 409, "detail": "...", "instance": "/api/bookings",
  "code": "SESSION_FULL", "timestamp": "2026-10-02T08:00:00Z", "traceId": "abc123",
  "errors": [ { "field": "capacity", "message": "...", "rejectedValue": 0 } ] }
```
`errors` appears only on validation failures. 5xx never exposes messages, stack traces or SQL.

### Error pipeline
1. Code throws a `BusinessException` subclass carrying an `ErrorCode` (`ResourceNotFoundException`, `InvalidTimeRangeException`, `BusinessRuleViolationException`, or `new BusinessException(ErrorCode.X, detail)`).
2. `GlobalExceptionHandler` (`@RestControllerAdvice`, extends `ResponseEntityExceptionHandler`) maps it; `ProblemDetailFactory` adds `code`, `timestamp`, `traceId`, `errors`.
3. Controllers never build error responses.

| HTTP | `ErrorCode` | Raised by |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean Validation on `@Valid` bodies / parameters |
| 400 | `MALFORMED_REQUEST` | Unreadable JSON |
| 400 | `INVALID_PARAMETER` | Type mismatch, missing parameter |
| 400 | `INVALID_TIME_RANGE` | `InvalidTimeRangeException` |
| 404 | `RESOURCE_NOT_FOUND` | `ResourceNotFoundException` |
| 404 | `ENDPOINT_NOT_FOUND` | Unknown path |
| 405 | `METHOD_NOT_ALLOWED` | Unsupported method |
| 409 | `RESOURCE_IN_USE` | Any `DataIntegrityViolationException` (e.g. deleting a referenced row) |
| 409 | `BUSINESS_RULE_VIOLATION` | `BusinessRuleViolationException` |
| 409 | `CONCURRENT_MODIFICATION` | `ObjectOptimisticLockingFailureException`, optimistic retries exhausted |
| 409 | `SESSION_FULL`, `DUPLICATE_BOOKING`, `LOCK_TIMEOUT` | Booking flow |
| 500 | `INTERNAL_ERROR` | Any other exception |

Adding an error: new `ErrorCode` constant (status, title, default detail) → throw it → add it to the endpoint's `@ApiResponses`.

### Validation
- Shape and format: Bean Validation on request records (`@NotNull`, `@NotBlank`, `@Size`, `@Positive`), triggered by `@Valid` in the controller.
- Business rules: entity methods or services, thrown as `BusinessException`.
- Configuration: `@Validated` `@ConfigurationProperties` records with `@DefaultValue`; invalid config fails startup.

### Tracing and logging
- `X-Request-Id` is reused or generated, put in MDC `traceId`, echoed in the response and in Problem Details.
- `common/logging` logs every request and SQL statement; business code logs only business events, parameterized, no personal data.

## 5. Commands

| Task | Command |
|---|---|
| Full build + all tests | `./mvnw verify` |
| One test class | `./mvnw test -Dtest=ClassName` |
| Load simulations (excluded by default) | `./mvnw test -Dsurefire.excludedGroups= -Dtest=BookingConcurrencySimulationIntegrationTest` |
| Run locally (starts Postgres, Redis, RedisInsight via Docker Compose) | `LOG_PATH=./logs ./mvnw spring-boot:run -Dspring-boot.run.profiles=local` |
| Start compose services manually (if Postgres already runs) | `docker compose up -d` |
| Reset local data | `docker compose down -v` |
| Redis CLI | `docker compose exec redis redis-cli` |
| Swagger UI / health | `http://localhost:8080/swagger-ui.html`, `/api/health` |
| Held booking locks (local) | `http://localhost:8080/actuator/bookinglocks` |

Profiles: `application.yaml` (shared + production defaults), `local` (dev machine and all tests via surefire), `dev` (shared server), `benchmark` (load tests, request/SQL logging off).

Tests:

| Kind | Naming | Setup |
|---|---|---|
| Unit | `XxxTest` | No Spring, no DB; Mockito |
| Integration | `XxxIntegrationTest` | `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)` (Postgres + Redis containers) |
| Fixtures | `IntegrationTestData` (JDBC inserts, `truncateAll()`), `RedisTestData.flushAll()`, `HeldBookingLock` | Reset state in `@BeforeEach`; ids restart after truncate |
| Simulation | `@Tag("simulation")` | Excluded from `verify`; uses Docker Compose |

Test overrides for the `local` profile go in `src/test/resources/config/application-local.yaml` (it overlays, not replaces, the main file).

## 6. Don'ts

- Never hard-code secrets, passwords or tokens; use environment variables (`REDIS_URL`, `SPRING_DATASOURCE_*`).
- Never log personal data, credentials or full payloads from business code.
- Never skip validation: no `@Valid` removal, no business rule only in the controller, no unchecked input reaching the service.
- Never return entities or build error responses in controllers; never expose exception messages, stack traces or SQL in 5xx.
- Never change `bookedCount` outside the booking strategies, and never add a setter for it.
- Never inject another module's repository; never add a dependency from `session`/`member`/`court` to `booking`.
- Never edit a merged Flyway migration or switch `ddl-auto` away from `validate`; if an entity cannot match the schema, stop and ask.
- Never use native SQL where JPQL works, string-concatenated SQL, or derived bulk `deleteBy`.
- Never modify entities inside a read-only transaction, call external services inside a transaction, or release a booking lock before commit.
- Never use `@Data`, `@ToString`, `@EqualsAndHashCode`, `@Value`, setters or eager `@ManyToOne` on entities.
- Never use `System.out`, `printStackTrace`, string-concatenated log messages, wildcard imports, magic numbers or emoji.
- Never delete, weaken or `@Disabled` a test to get a green build; never change production code only to satisfy a bad test.
- Never set `app.booking.distributed-lock.debug-hold` outside local; never use `KEYS` against Redis in code (use `SCAN`).
- Never add a dependency outside an approved plan; never run `git add/commit/push/rebase/reset`.

## 7. Known inconsistencies (do not "fix" without asking)
- `booking` uses layered subpackages while other modules are flat.
- Repositories are `public` although `CLAUDE.md` says package-private.
- Flyway `locations` lists `classpath:db/seed`, which does not exist.
