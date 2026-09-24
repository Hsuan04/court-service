# court-service

Side project for practicing high-concurrency court booking with Spring Boot.
The core problem: many members booking the same session at once must never exceed its capacity.

## Tech Stack
- Java 21, Spring Boot 4.1.1, Maven
- PostgreSQL 17, Flyway, Spring Data JPA, Lombok
- springdoc-openapi (Swagger UI)
- JUnit 5, AssertJ, Mockito, Testcontainers

## Commands
- Full build with all tests: `./mvnw verify`
- Run a single test class: `./mvnw test -Dtest=ClassName`
- Run locally: `./mvnw spring-boot:run` (Spring Boot Docker Compose starts PostgreSQL automatically)
- Reset local database: `docker compose down -v`
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health check: http://localhost:8080/api/health

## Development Workflow
Follow these steps for every task, in order:

1. **Explore**: read this file, the related code and existing tests before proposing anything
2. **Plan**: list the files to add or change, the approach, and any new dependency with its reason; wait for approval
3. **Implement**: stay within the approved plan and the scope of the current issue
4. **Test**: add or update tests for every change (see Testing)
5. **Verify**: run `./mvnw verify` and fix failures until the build is green
6. **Report**: finish with the report described in Completion Report

Rules:
- If a requirement is ambiguous, ask instead of guessing
- If something outside the current issue needs changing, report it instead of implementing it
- Never add a dependency that was not listed in the approved plan

## Definition of Done
A task is complete only when all of the following are true:
- [ ] Every change is covered by unit or integration tests
- [ ] `./mvnw verify` passes with no failing, skipped or disabled tests introduced
- [ ] No compiler warnings introduced
- [ ] New public classes and non-trivial public methods have Javadoc
- [ ] New endpoints and DTOs have complete Swagger annotations
- [ ] No leftover debug code, commented-out code or unused imports
- [ ] Changes are left uncommitted

## Git Workflow
- Branches: `<type>/<issue-number>-<description>`, based on develop
- Never run `git add`, `git commit`, `git push`, `git rebase`, `git reset` or any command that changes Git history or the staging area
- Leave all changes uncommitted; the developer reviews, commits and pushes
- Propose a commit plan in the Completion Report instead
- Commit messages follow Conventional Commits: feat, fix, perf, refactor, test, docs, ci, chore

## Project Structure
Package by feature under `com.courtservice`:
- `court/`, `member/`, `session/`, `booking/`: one package per domain
- Entity, Repository, Service and Controller live directly in the feature package; request and response records live in a `dto/` subpackage
- `common/`: BaseEntity, error handling, shared web types, configuration, health check
- Migrations: `src/main/resources/db/migration`

## Module Boundaries
- Repositories are package-private
- A module never injects another module's Repository; it calls the owning module's Service instead

## Domain Rules
- A CourtSession is capacity-based: many members share one session up to `capacity`
- `booked_count` must never exceed `capacity` (enforced by a DB CHECK constraint)
- A member can book the same session only once (enforced by a DB UNIQUE constraint)
- `CourtSession.bookedCount` must only change through the booking flow, never through CRUD APIs or setters

## API Conventions
- RESTful resources: plural nouns in kebab-case, no verbs (e.g. `/api/court-sessions`)
- Sub-resources use nested paths (e.g. `/api/court-sessions/{id}/bookings`)
- GET reads, POST creates, PUT replaces, DELETE removes
- Status codes: 201 with `Location` header on create, 200 on read and update, 204 with no body on delete
- Successful responses return the resource directly, without an envelope
- List endpoints are always paginated through optional `page` and `size` query parameters and return `PageResponse`
- Errors use RFC 9457 Problem Details (`application/problem+json`) with extension fields `code`, `timestamp`, `traceId` and, for validation errors, `errors`
- 4xx means a client-correctable error; 5xx means an unexpected server error
- 5xx responses never expose exception messages, stack traces or SQL
- Expected errors are thrown as subclasses of `BusinessException`; controllers never build error responses manually
- Every request carries a trace ID via the `X-Request-Id` header and MDC

## Swagger Conventions
- All documentation text is in English
- Every controller has `@Tag`
- Every endpoint has `@Operation` (summary and description) and `@ApiResponses` covering all possible status codes
- Error responses reference the shared Problem Details schema
- Every request and response field has `@Schema` with description and example
- Read-only fields (e.g. `bookedCount`) are marked as read-only in `@Schema`

## Coding Conventions

### General
- Constructor injection only; dependencies are `private final`
- Use Lombok `@Slf4j` for logging; never use `System.out` or `printStackTrace`
- Use parameterized logging (`log.info("Created court {}", id)`), never string concatenation
- No wildcard imports
- No magic numbers or strings; use named constants or enums
- Return `Optional` only from lookup methods; never use it for fields or parameters
- Prefer immutable objects; records for DTOs and value types

### Entities
- Use `@Getter` and `@NoArgsConstructor(access = AccessLevel.PROTECTED)` only
- Never use `@Data`, `@ToString`, `@EqualsAndHashCode` or `@Value` on entities:
    - `@ToString` reads lazy associations, which triggers extra queries or `LazyInitializationException`, and recurses infinitely on bidirectional associations
    - `@EqualsAndHashCode` over mutable fields changes an entity's hash code after persist, breaking `Set` and `Map` behavior
    - `@Data` includes both of the above plus setters
- Entities keep default `Object` identity for `equals` and `hashCode`
- No setters; change state through named business methods that enforce their own invariants
- `@ManyToOne` is always `fetch = FetchType.LAZY`

### DTOs, Services and Controllers
- DTOs are Java records; controllers never return entities
- Map entities to response records inside the service transaction
- Services use class-level `@Transactional(readOnly = true)`; write methods are annotated with `@Transactional`
- Never modify entities inside a read-only transaction; changes are silently discarded
- Keep transactions short; never call external services inside a transaction
- Validate input at the controller boundary with Bean Validation; enforce business rules in entities or services

### Data Access
- Avoid N+1 queries: list queries that need associations use `@EntityGraph` or DTO projections
- If only the id of an association is needed, read `getId()` from the proxy instead of fetching the entity
- Use `getReferenceById` when an association is needed only to set a foreign key
- Bulk deletes and updates use `@Modifying` queries, not derived `deleteBy` methods

## Comments and Documentation
- Write all code comments and Javadoc in English
- Never use emoji anywhere: code, comments, Javadoc, log messages, test names, documentation or commit messages
- Add Javadoc to every public class and every non-trivial public method, describing purpose, parameters, return value and thrown exceptions
- Comments explain why, not what; do not restate what the code already says
- Explicitly document non-obvious decisions: concurrency handling, transaction boundaries, locking, and trade-offs
- Mark follow-up work as `// TODO(#issue-number): description`; never leave a TODO without an issue number
- Never leave commented-out code

## Testing
Every change must come with tests. A change without tests is not complete.

### What to test
- Unit tests for entity business methods and pure logic (no Spring context, no database)
- Integration tests for controllers, repositories and anything touching the database, using Testcontainers
- Every bug fix starts with a failing test that reproduces the bug
- Cover the success path, validation failures, not-found cases and business rule violations

### How to write tests
- JUnit 5 with AssertJ assertions
- Test class naming: `XxxTest` for unit tests, `XxxIntegrationTest` for integration tests
- Test method names describe behavior (e.g. `sameMemberCannotBookSameSessionTwice`)
- Structure each test as given / when / then
- Each test is independent; never rely on execution order or shared mutable state
- Integration tests use the existing `TestcontainersConfiguration`
- Use `saveAndFlush` or `flush` when testing database constraints
- Concurrency tests must assert exact results (e.g. 1000 requests, capacity 100, exactly 100 succeed)

### Never
- Never delete, weaken, skip or `@Disabled` an existing test to make the build pass
- Never change production code only to make a poorly written test pass; fix the test instead and explain why

## Database
- Schema is owned by Flyway; `ddl-auto` is `validate`
- Never modify a migration that has been merged into develop; add a new version instead
- If an entity cannot match the existing schema, stop and explain instead of changing migrations

## Security
- Never hard-code secrets, passwords or tokens; use configuration and environment variables
- Never log personal data or credentials

## Completion Report
End every task with a report containing:
1. **Summary**: what was done
2. **Design decisions**: choices made and why, including alternatives considered
3. **Acceptance criteria**: each criterion of the current issue and whether it is met
4. **Tests**: tests added or changed, and the result of `./mvnw verify`
5. **Commit plan**: proposed commits with Conventional Commits messages (do not execute them)
6. **Open questions**: anything that needs the developer's decision