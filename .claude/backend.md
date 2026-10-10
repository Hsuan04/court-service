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
| Redis | Redis + Redisson: distributed lock (`distributed-lock`) and seat stock via Lua scripts (`redis-cache`, `redis-lua`) | 7.4 / 4.7.0 |
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
| `booking/` | `controller/`, `service/`, `repository/`, `entity/`, `dto/`, `config/` (`BookingProperties`), `cache/` (sold-out flag), `lock/` (lock keys, actuator endpoint), `stock/` (Redis seat stock: keys, Lua script runner, synchronizer), `event/` (`BookingCancelledEvent`), `strategy/` + `strategy/impl/` | Layered because of the strategy family; keep new booking code in the matching subpackage |
| `common/` | `BaseEntity`, `error/`, `web/` (paging, `TraceIdFilter`), `logging/`, `config/` (OpenAPI, Redisson), `health/` | Shared infrastructure only, no business rules |
| `src/main/resources/db/migration` | Flyway scripts | Schema source of truth |
| `src/main/resources/redis/booking` | Lua scripts of the seat stock (`reserve_seat`, `init_seat_stock`, `release_seat`, `adjust_seat_stock`) | Run only through `SeatStockScripts`; each file documents its keys, arguments and return codes |

Dependency rules:
- Controller → Service → Repository. Controllers never touch repositories or entities.
- Cross-module calls go through the owning module's **Service** (e.g. booking → `CourtSessionService`, `MemberService`), never another module's Repository. Repositories are `public` only for Java visibility.
- `booking` depends on `session` and `member`; nothing depends on `booking`. Reverse notifications use events, always handled `AFTER_COMMIT` so a rolled-back change never leaks out:

| Event | Published by | Handled by |
|---|---|---|
| `CourtSessionChangedEvent` | `CourtSessionService#update`, `#delete` | `SoldOutSessionCache` (clears the flag) |
| `CourtSessionCapacityChangedEvent` (delta) | `CourtSessionService#update` when the capacity changed | `SeatStockSynchronizer` (adds the delta to the Redis stock) |
| `BookingCancelledEvent` | `BookingService#cancel` | `SeatStockSynchronizer` (gives the seat back), `SoldOutSessionCache` (clears the flag) |

- Booking writes go through `BookingStrategyRegistry.active()` (`app.booking.concurrency-mode`); each strategy owns its transaction (`BookingService#create` is `NOT_SUPPORTED`). Cancellation does not depend on the strategy (see 3.3).

## 3. Booking concurrency

The core problem: many members booking one session at once must never push `booked_count` past `capacity`. Each strategy is one way to solve it, selectable at startup; the race tests in `booking/concurrency/` compare them under the same load.

### 3.1 Strategies

| Mode | Class | Mechanism | Across instances | Under contention |
|---|---|---|---|---|
| `naive` | `NaiveBookingStrategy` | Read, check, write back `read + 1` | Unsafe (intentional: oversells, lost updates) | No protection |
| `synchronized` | `SynchronizedBookingStrategy` | One JVM-wide monitor around the transaction | No: other instances fail the `@Version` check (`CONCURRENT_MODIFICATION`) | Waits without timeout; every session queues on one lock |
| `jvm-lock` | `JvmLockBookingStrategy` | One `ReentrantLock` per session, in a Caffeine cache with weak values | No: same as `synchronized` | `LOCK_TIMEOUT` after `app.booking.jvm-lock.wait-time` |
| `pessimistic-lock` | `PessimisticLockBookingStrategy` | `SELECT ... FOR UPDATE` on the session row | Yes | Waits on the row lock |
| `optimistic-lock` | `OptimisticLockBookingStrategy` | `@Version` check, retried in a new transaction after a random backoff | Yes | `CONCURRENT_MODIFICATION` after `max-attempts` |
| `atomic-update` | `AtomicUpdateBookingStrategy` | One `UPDATE ... WHERE booked_count < capacity RETURNING` | Yes | `SESSION_FULL` straight from the statement |
| `distributed-lock` | `DistributedLockBookingStrategy` | Redisson lock `lock:court-session:{id}` around the transaction | Yes | `LOCK_TIMEOUT` after `app.booking.distributed-lock.wait-time` |
| `redis-cache` | `RedisCacheBookingStrategy` | `DECR` a seat counter in Redis, then the `atomic-update` write | Yes | `SESSION_FULL` from Redis without touching the database |
| `redis-lua` | `RedisLuaBookingStrategy` | One Lua script checks the member, checks the stock, deducts and records the member; then the `atomic-update` write | Yes | `SESSION_FULL` or `DUPLICATE_BOOKING` from Redis without touching the database |

Rules shared by every strategy:
- A lock taken by a strategy wraps the whole transaction, commit included, and is taken before the transaction begins, so waiters hold no database connection.
- The duplicate check counts only `CONFIRMED` bookings; the partial unique index `uq_booking_session_member` is the final guard, and `BookingService` translates its violation into `DUPLICATE_BOOKING` for every strategy.
- The sold-out flag (`SoldOutSessionCache`, `app.booking.sold-out-cache.*`) rejects a full session before any strategy runs; it is per instance and only a speed-up.
- Adding a strategy: a `BookingConcurrencyMode` constant, a `@Component` in `strategy/impl/` implementing `BookingStrategy`, and a race test extending `BookingRaceIntegrationTestBase` that asserts exact results.

### 3.2 Redis keys and seat stock

| Key | Type | Used by | Lifecycle |
|---|---|---|---|
| `lock:court-session:{id}` | Redisson lock | `distributed-lock` | Held during one booking; the watchdog renews it, no lease time |
| `booking:{court-session:id}:stock` | Integer string, seats left | `redis-cache`, `redis-lua` | Created by the first booking from `capacity - booked_count` ("set if absent", so concurrent initializations keep one value); no TTL; `+delta` on a capacity change, `+1` on a cancellation |
| `booking:{court-session:id}:members` | Set of member ids holding a seat | `redis-lua` | Created together with the stock from the `CONFIRMED` bookings; member added on reservation, removed on a failed booking or a cancellation |

- The `{court-session:id}` hash tag puts both stock keys of a session in one Redis Cluster slot, which a Lua script touching both requires.
- Redis is the gate that keeps traffic off the database; the database stays the last line of defense. Both Redis strategies write with the conditional `UPDATE ... WHERE booked_count < capacity`, so a stock that drifted from the truth can never oversell.
- Redis is never called inside a database transaction. When the database write fails after Redis granted a seat, the reservation is undone after the rollback:

| Database outcome | `redis-cache` | `redis-lua` |
|---|---|---|
| `SESSION_FULL` (Redis counted too many seats) | Seat not given back: that would widen the drift | Member removed, seat not given back |
| Any other failure (404, duplicate, unexpected exception) | `INCR` gives the seat back | Seat given back (only if the stock still exists) and member removed |

- `redis-cache` is not atomic between `DECR` and checking its result: a request that drove the counter below zero adds the seat back. Repeated requests of one member each take a seat until the database rejects them. `redis-lua` removes both problems.
- The stock is never deleted to resynchronize: a reload from `booked_count` does not count seats reserved in Redis but not yet committed, and would hand them out twice. Capacity changes add the difference instead.
- Inspect locally: `docker compose exec redis redis-cli GET 'booking:{court-session:1}:stock'` and `SMEMBERS 'booking:{court-session:1}:members'`.

### 3.3 Cancellation

`DELETE /api/bookings/{id}` (`BookingService#cancel`) is a soft cancel, identical for every strategy:
1. `BookingRepository#cancelIfConfirmed`: conditional `UPDATE ... SET status = CANCELLED WHERE id = ? AND status = CONFIRMED`. 0 rows means the booking was already cancelled → `BOOKING_ALREADY_CANCELLED`. Of two concurrent cancellations the second waits for the row lock, then matches nothing, so exactly one succeeds.
2. `CourtSessionService#releaseSeat`: conditional `booked_count - 1, version + 1 WHERE booked_count > 0`. 0 rows means the counter disagrees with the bookings → `IllegalStateException`, rollback. The version bump makes a lock-based strategy (`synchronized`, `jvm-lock`, `distributed-lock`) that read the old count fail with `CONCURRENT_MODIFICATION` rather than overwrite the release.
3. After commit, `BookingCancelledEvent` gives the seat back to the Redis stock (only if it exists) and removes the member from the set, and clears this instance's sold-out flag.

The cancelled row stays and can still be read; the member may book the session again because only `CONFIRMED` bookings are unique. A session with cancelled bookings still cannot be deleted (`RESOURCE_IN_USE`, the foreign key still references it).

## 4. Database and ORM

| Topic | Rule |
|---|---|
| Schema owner | Flyway; Hibernate only validates (`ddl-auto: validate`) |
| New change | Add `V<n>__<snake_case_description>.sql`; never edit a migration merged into `develop` |
| Running migrations | Automatic on application start and in every Spring test |
| Tables / columns | Singular `snake_case` (`court_session`, `booked_count`) |
| Constraints | `chk_<table>_<rule>`, `uq_<table>_<cols>` (unique constraint or unique index), `idx_<table>_<col>`; FKs inline `REFERENCES`. `uq_booking_session_member` is a partial unique index (`WHERE status = 'CONFIRMED'`, migration V2), so a cancelled booking does not block rebooking |
| Entities | Extend `BaseEntity` (`id` IDENTITY, `createdAt`, `updatedAt`); `@Getter` + `@NoArgsConstructor(PROTECTED)` only; no setters, no `@Data/@ToString/@EqualsAndHashCode/@Value`; `@ManyToOne(fetch = LAZY)` |
| Invariants | Enforced twice: entity methods or conditional statements (`CourtSession.book()`, `changeCapacity()`, the conditional seat updates) and DB constraints (`chk_session_booked`, `uq_booking_session_member`) |
| Queries | Derived methods or JPQL. Native SQL only when JPQL cannot express it (current single case: `UPDATE ... RETURNING` in `CourtSessionRepository`); document why |
| Bulk writes | `@Modifying` JPQL, not derived `deleteBy`/`updateBy`. Writes that must not race (cancel a booking, release a seat) are conditional `@Modifying` updates that return the row count, with `clearAutomatically` so no stale entity is read afterwards |
| N+1 | `@EntityGraph` or DTO projections for list queries; read `getId()` from lazy proxies; `getReferenceById` to set FKs |
| Transactions | Services: class-level `@Transactional(readOnly = true)`, writes annotated `@Transactional`; map to DTOs inside the transaction; no external calls inside |
| `booked_count` | Changed only by booking strategies and by `BookingService#cancel` (through `CourtSessionService#releaseSeat`), never by CRUD |

## 5. API and errors

### Conventions
- Plural kebab-case resources, nested sub-resources: `/api/courts`, `/api/members`, `/api/court-sessions`, `/api/court-sessions/{id}/bookings`, `/api/members/{id}/bookings`, `/api/bookings`.
- `DELETE /api/bookings/{id}` cancels rather than removes: the booking stays readable with status `CANCELLED` (see 3.3).
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
| 409 | `CONCURRENT_MODIFICATION` | `ObjectOptimisticLockingFailureException`: optimistic retries exhausted, another instance under `synchronized`/`jvm-lock`, or a lock-based booking racing a cancellation |
| 409 | `SESSION_FULL`, `DUPLICATE_BOOKING`, `LOCK_TIMEOUT` | Booking flow (the Redis strategies raise `SESSION_FULL`/`DUPLICATE_BOOKING` from Redis) |
| 409 | `BOOKING_ALREADY_CANCELLED` | Cancelling a booking that is already cancelled |
| 500 | `INTERNAL_ERROR` | Any other exception |

Adding an error: new `ErrorCode` constant (status, title, default detail) → throw it → add it to the endpoint's `@ApiResponses`.

### Validation
- Shape and format: Bean Validation on request records (`@NotNull`, `@NotBlank`, `@Size`, `@Positive`), triggered by `@Valid` in the controller.
- Business rules: entity methods or services, thrown as `BusinessException`.
- Configuration: `@Validated` `@ConfigurationProperties` records with `@DefaultValue`; invalid config fails startup.

### Tracing and logging
- `X-Request-Id` is reused or generated, put in MDC `traceId`, echoed in the response and in Problem Details.
- `common/logging` logs every request and SQL statement; business code logs only business events, parameterized, no personal data.

## 6. Commands

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
| Seat stock of a session (`redis-cache`, `redis-lua`) | `docker compose exec redis redis-cli GET 'booking:{court-session:1}:stock'`, `SMEMBERS 'booking:{court-session:1}:members'` |

Profiles: `application.yaml` (shared + production defaults), `local` (dev machine and all tests via surefire), `dev` (shared server), `benchmark` (load tests, request/SQL logging off).

Tests:

| Kind | Naming | Setup |
|---|---|---|
| Unit | `XxxTest` | No Spring, no DB; Mockito |
| Integration | `XxxIntegrationTest` | `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)` (Postgres + Redis containers) |
| Fixtures | `IntegrationTestData` (JDBC inserts incl. bookings in any status, `truncateAll()`), `RedisTestData.flushAll()` (locks and seat stock), `HeldBookingLock` | Reset state in `@BeforeEach`; ids restart after truncate |
| Booking races | `BookingRaceIntegrationTestBase`: `bookingServiceUsing(strategy)`, `race(...)`, `assertFilledExactly(...)`, `cancel(bookingId)`, `changeCapacity(...)` | Services from `bookingServiceUsing` are plain objects, not Spring proxies: cancel through `cancel(bookingId)`, which uses the application bean so `@Transactional` applies |
| Simulation | `@Tag("simulation")` | Excluded from `verify`; uses Docker Compose |

Test overrides for the `local` profile go in `src/test/resources/config/application-local.yaml` (it overlays, not replaces, the main file).

## 7. Don'ts

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
- Never call Redis inside a database transaction; undo a Redis reservation only after the rollback.
- Never write the seat stock keys outside `SeatStockScripts` and `RedisCacheBookingStrategy`; never give them a TTL or delete them to resynchronize (see 3.2).
- Never count `CANCELLED` bookings as taken seats: duplicate checks and the Redis member set use `CONFIRMED` only.
- Never add a dependency outside an approved plan; never run `git add/commit/push/rebase/reset`.

## 8. Known inconsistencies and limitations (do not "fix" without asking)
- `booking` uses layered subpackages while other modules are flat.
- Repositories are `public` although `CLAUDE.md` says package-private.
- Flyway `locations` lists `classpath:db/seed`, which does not exist.
- No Redis/database reconciliation: an instance that dies between the Redis deduction and the commit loses that seat from the stock (and, under `redis-lua`, leaves the member in the set). Manual recovery: while no booking of the session is in flight, delete both stock keys of the session; the next booking reinitializes them from the database.
- Redis updates after a capacity change or a cancellation run after commit and are logged, not retried, if Redis fails; the stock then stays off until it is rebuilt. The database still prevents any oversell.
- The sold-out flag is cleared only on the instance that handled the change; other instances wait for the TTL.