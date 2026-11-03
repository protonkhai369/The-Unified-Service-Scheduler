# Unified Service Scheduler - agent guide

Shared instructions for every AI coding agent in this repository (Claude Code, Codex, Antigravity).
Agent-specific files (`CLAUDE.md`, `.codex/rules/PROJECT.md`, `.agent/rules/PROJECT.md`) only add
tool details; this file is the source of truth.

Keyloop technical assessment, **Scenario A** (Ownership domain): a Spring Boot REST backend that books
dealership service appointments, guaranteeing a service bay and a qualified technician are both free
for the whole service duration. Only the backend is built; clients are stubbed with OpenAPI/Swagger UI
and cURL. The brief is `KeyloopCodingChallange.pdf` (not committed).

**Current state:** the application code was reset to a bare skeleton on 2026-10-30 (entry point,
configuration, base test). `openspec/specs/` describes the target behaviour; features are rebuilt
through OpenSpec changes. The database schema (`V1__scheduler_schema.sql`) and demo data exist since
change `add-scheduler-database`. Change `add-api-foundation` added the error pipeline, the request id
and access log, and the read-only catalog endpoints (`/api/v1/service-types`, `/api/v1/dealerships`)
with their JPA entities. Change `add-appointment-booking` added booking, reading and cancelling
appointments (`/api/v1/appointments`), the pure `scheduling` domain, idempotency and booking metrics.
Change `add-availability-search` added the read-only availability search
(`/api/v1/dealerships/{id}/availability`), which lists the start times booking would accept by
reusing booking's rules (`StartTimeRule`, `FreeResources`) and never takes the booking lock. Every
specified capability now exists.
Change `add-supabase-auth` made the backend an OAuth2 resource server for Supabase Auth access tokens,
with roles `customer`, `advisor` and `admin` from `app_metadata`. Change `add-self-service-queries`
added `/api/v1/me`, `/api/v1/me/vehicles`, `/api/v1/me/appointments` and a dealership's day
(`/api/v1/dealerships/{id}/appointments?date=`). Change `add-customer-search` added staff search of
customers by name or email (`/api/v1/customers?q=`), accent- and typo-tolerant with `unaccent` and
`pg_trgm` (migration `V2`).

## Commands

```bash
./mvnw test                 # tests on a throwaway Testcontainers PostgreSQL (Docker must be running)
./mvnw spring-boot:run      # uses the database in .env (Supabase); falls back to localhost:5432
openspec validate --all --strict
```

This machine's default `JAVA_HOME` is JDK 17, but the project targets Java 21. Prefix Maven commands
with `JAVA_HOME="/c/Program Files/Java/jdk-23"` (Git Bash) unless `JAVA_HOME` has been changed.

## Spec-driven workflow (OpenSpec)

`openspec/specs/` is the source of truth for behaviour. `openspec/config.yaml` holds the project
context and per-artifact rules that every proposal must follow.

- Any change to externally observable behaviour (API, status codes, persisted data, metrics) goes
  through OpenSpec: propose -> human review of proposal/design/tasks -> apply -> verify -> archive.
  Use explore to think a problem through first.
- Pure refactors, test-only changes and typo fixes may skip the proposal.
- When code and spec disagree, stop and ask which one is wrong; do not silently change either.

| Step | Claude Code | Codex | Antigravity |
| --- | --- | --- | --- |
| Think it through | `/opsx:explore` | `/prompts:opsx-explore` | `/opsx-explore` |
| Proposal + specs + design + tasks | `/opsx:propose` | `/prompts:opsx-propose` | `/opsx-propose` |
| Implement tasks | `/opsx:apply` | `/prompts:opsx-apply` | `/opsx-apply` |
| Check code against the change | `/opsx:verify` | `/prompts:opsx-verify` | `/opsx-verify` |
| Merge into main specs | `/opsx:archive` | `/prompts:opsx-archive` | `/opsx-archive` |

The same steps are also available as `openspec-*` skills, triggered by asking in plain language.

## Which skill to use

| Task | Skill |
| --- | --- |
| Controllers, services, configuration, Spring idioms | `java-springboot` |
| Any test; choosing a slice; Spring Boot 4 test APIs | `spring-boot-testing` |
| Testcontainers lifecycle, reuse, Docker problems | `java-testcontainers` |
| Entities, JPQL, fetching, locking, transactions | `java-hibernate-jpa` |
| Schema, indexes, constraints, migrations | `postgresql-table-design` |
| Endpoint design, status codes, versioning | `api-design-principles` |
| Dockerfile | `multi-stage-dockerfile` |
| New feature or bug fix | `test-driven-development` |
| Failing test, bug, unexpected behaviour | `systematic-debugging` |
| Before saying anything is done, fixed or passing | `verification-before-completion` |
| Security pass | Claude Code: built-in `/security-review`; Codex and Antigravity: `security-review` skill |

Where each skill comes from and why it was chosen: `docs/AGENT_SKILLS.md`. Reinstall or copy the
whole toolkit to another project with `scripts/install-agent-skills.sh`.

## Project rules

- Stack is fixed unless a proposal changes it: Java 21, Spring Boot 4.1.2 (Spring Framework 7,
  Hibernate 7, **Jackson 3** - `tools.jackson.*`), PostgreSQL 17, Flyway, springdoc 3.1.1,
  Testcontainers 2 (`org.testcontainers.postgresql.PostgreSQLContainer`), Spring Security 7 as an
  OAuth2 resource server, Lombok (entities only, see below). Use Boot 4 APIs: `@MockitoBean`, modular `spring-boot-starter-*-test` artifacts.
- Database: Supabase-hosted PostgreSQL 17 through the session pooler (IPv4; supports prepared
  statements). Credentials live only in the git-ignored `.env` (template `.env.example`), never in
  code, `application.yml`, docs or commits. Do not use the transaction pooler (port 6543).
- Tests MUST run on `TestcontainersConfiguration` (a throwaway PostgreSQL 17), never on Supabase:
  tests create and truncate data.
- Authentication is Supabase Auth; the backend only verifies access tokens (JWKS from `SUPABASE_URL`,
  ES256) and holds no Supabase secret. `SUPABASE_SECRET_KEY` in `.env` is for
  `scripts/supabase-demo-users.py` only, which creates users in the live project: run it only when
  the user asks.
- Security is deny by default: a new endpoint needs a token unless it is added to the public list in
  `security.SecurityConfiguration`. Read roles only from `app_metadata`, never from `user_metadata`
  (users can edit it). Rules that need the request body or the appointment live in the pure
  `security.AccessPolicy`, and services check them first: bookings before the idempotency replay,
  cancellations before the update. An appointment the caller may not see is `404`, not `403`.
- Use `@PreAuthorize` (method security is on), calling `AccessPolicy` from SpEL, on a `public`
  service method when the caller and the arguments alone decide (the `/me` lists, a dealership's
  day). Check inside the service when the decision needs loaded data or must sit between business
  steps (booking, reading or cancelling one appointment). `GlobalExceptionHandler` turns
  `AccessDeniedException` into `403 FORBIDDEN`; without that handler its catch-all would answer `500`.
- Tests never call Supabase Auth. `DatabaseIntegrationTest.tokens` (`TestTokens`) signs tokens that
  the application's real decoder accepts. The `AppointmentApiTestSupport` helpers call as admin; use
  `bookAs`, `getAs` and `cancelAs` for other roles.
- Double booking MUST be prevented inside the database with `EXCLUDE USING gist` constraints
  (`btree_gist` is available on Supabase); application-side checks are only an optimisation. Each
  booking attempt first takes `pg_advisory_xact_lock` on its dealership and local day, so concurrent
  requests queue instead of failing because of each other; any new code that writes appointments
  must take the same lock. A booking that still loses a race (or is a deadlock victim) retries in a
  new transaction. Classify database errors by SQLSTATE and constraint name from `PSQLException`,
  never by message text.
- "Now" comes only from the `Clock` bean. Tests move it through the `MutableClock` that
  `ProbeConfiguration` registers and `DatabaseIntegrationTest` resets before each test.
- Keep scheduling rules (time ranges, allocation, slot finding) in a pure domain package with no
  Spring or JPA, so they stay unit-testable.
- Package by feature at the top level (`catalog`, `appointment`, `availability`, ...); inside a
  feature, package by layer: `controller`, `service`, `repository`, `entity`, `dto/request` and
  `dto/response` (e.g. `catalog.controller`, `catalog.dto.response`). The pure scheduling domain is
  not layered. The shared `web` package is split by function: `web.error` (`ErrorCode`,
  `ApiException`, the exception handler, the /error endpoint, `Problems`), `web.filter` (request id,
  access log) and `web.dto` (`ItemsResponse`).
- Schema changes are new Flyway migrations (`V2__...` onwards); never edit a migration that has been
  applied anywhere. `ddl-auto` stays `validate`.
- Every table lives in schema `scheduler`, never `public`: Supabase exposes `public` to anonymous
  callers through its Data API. Extensions go in schema `extensions`. V1 already revokes the
  `anon`/`authenticated` roles' default privileges, so new tables need no extra grants or RLS.
  The application's `search_path` is `scheduler` only, so SQL that uses an extension names it:
  `extensions.similarity(...)`, `OPERATOR(extensions.%)`, `extensions.gin_trgm_ops`. Accent-insensitive
  comparisons use the `IMMUTABLE` wrapper `f_unaccent`, never `unaccent()` directly, so an index can
  serve them.
- `src/test/resources/db/probe/V<n>__probe.sql` always stands for "the next schema migration": when a
  real `V<n>` is added, renumber the probe and `DemoDataIntegrationTest`'s expected version.
- Demo data lives only in `src/main/resources/db/demo/R__demo_data.sql`, a repeatable migration that
  inserts only when `dealership` is empty. Never put demo rows in `db/migration` or in a versioned
  migration. The application loads both locations; tests load `db/migration` only.
- Database and HTTP tests extend `DatabaseIntegrationTest` (one shared context, container and
  embedded server on a random port, tables truncated before each test; `RestTestClient http`) and
  are named `*IntegrationTest`; Surefire runs them with `./mvnw test`. Assert database rejections by
  SQLSTATE and constraint name (`SqlAssertions`). Test-only endpoints and filters go in
  `ProbeConfiguration`, which the base imports; importing a configuration into one test class would
  start a second context and container.
- Store `Instant` / `timestamptz`; convert to the dealership's zone only at the API edge. Do not set
  `hibernate.jdbc.time_zone`: it shifts `time` columns on machines whose zone is not UTC.
- A successful response body is the resource itself (no `{success, data}` envelope); collections are
  `ItemsResponse<T>` (`{"items": [...]}`) ordered by id.
- Errors are RFC 9457 problem+json with a stable `code`. Services fail with
  `new ApiException(ErrorCode.X, args)`; a new code is a new `ErrorCode` constant added together with
  the spec text that defines it (`ErrorCodeTest` fails otherwise). The pure domain never throws
  `ApiException`.
- Never annotate a controller with `@Validated`: parameter constraints rely on Spring MVC's built-in
  method validation, and `@Validated` would turn their violations into 500s.
- Lombok is used for `@Getter` on JPA entities only. No `@Setter` (state changes go through
  factories and conditional updates), no `@Data`/`@EqualsAndHashCode` (a hash over mutable fields
  breaks entities in sets and Hibernate's identity), no `@ToString`, no `@Builder` (it bypasses
  factory invariants such as `Appointment.confirmed`). DTOs are records with static `of()` mappers;
  there is no MapStruct. Lombok runs from the `maven-compiler-plugin` processor path, because JDK 23+
  does not run processors found on the class path.
- TDD applies to new work. Do not delete or rewrite existing, tested code just to redo it test-first.
- Log non-trivial AI decisions and verification steps in `docs/AI_LOG.md`; "AI Engineering &
  Verification" is a scored criterion of the assessment.

Each agent folder has an `archive/` subfolder holding configuration copied from an unrelated project
(StudyHub: Android, Kotlin, Supabase). Agents do not load it; never apply its rules here.
