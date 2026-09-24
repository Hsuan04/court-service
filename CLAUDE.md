# court-service

Side project for practicing high-concurrency court booking with Spring Boot.
The core problem: many members booking the same session at once must never exceed its capacity.

## Tech Stack
- Java 21, Spring Boot 4.1.1, Maven
- PostgreSQL 17, Flyway, Spring Data JPA, Lombok
- JUnit 5, Testcontainers

## Commands
- Build and test: `./mvnw verify`
- Run locally: start the app from the IDE; Spring Boot Docker Compose starts PostgreSQL automatically
- Reset local database: `docker compose down -v`

## Project Structure
Package by feature under `com.courtservice`:
- `court/`, `member/`, `session/`, `booking/` — one package per domain
- `common/` — BaseEntity, exceptions, shared response types
- Migrations: `src/main/resources/db/migration`

## Domain Rules
- A CourtSession is capacity-based: many members share one session up to `capacity`
- `booked_count` must never exceed `capacity` (enforced by a DB CHECK constraint)
- A member can book the same session only once (enforced by a DB UNIQUE constraint)
- `CourtSession.bookedCount` must only change through the booking flow, never through CRUD APIs or setters

## Coding Conventions
- Entities: no `@Data`; use `@Getter` and `@NoArgsConstructor(access = AccessLevel.PROTECTED)`; no setters; change state through named business methods
- `@ManyToOne` is always `fetch = FetchType.LAZY`
- DTOs are Java records; controllers never return entities
- Services use constructor injection; class-level `@Transactional(readOnly = true)`, write methods annotated with `@Transactional`
- Keep transactions short; never call external services inside a transaction
- Errors are returned as `ProblemDetail` through a global `@RestControllerAdvice`

## Database
- Schema is owned by Flyway; `ddl-auto` is `validate`
- Never modify a migration that has been merged into develop; add a new version instead
- If an entity cannot match the existing schema, stop and explain instead of changing migrations

## Testing
- Integration tests use Testcontainers with the existing `TestcontainersConfiguration`
- Use `saveAndFlush` or `flush` when testing database constraints
- Concurrency tests must assert exact results (e.g. 1000 requests, capacity 100, exactly 100 succeed)

## Git Workflow
- Branches: `<type>/<issue-number>-<description>`, based on develop
- Commits follow Conventional Commits: feat, fix, perf, refactor, test, docs, ci, chore
- Never commit to main or develop directly
- Do not push; the developer reviews and pushes
- Stay within the scope of the current issue; report anything out of scope instead of implementing it