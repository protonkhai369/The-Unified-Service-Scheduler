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
change `add-scheduler-database`; there are no JPA entities or REST endpoints yet.

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
  Testcontainers 2 (`org.testcontainers.postgresql.PostgreSQLContainer`). Use Boot 4 APIs:
  `@MockitoBean`, modular `spring-boot-starter-*-test` artifacts.
- Database: Supabase-hosted PostgreSQL 17 through the session pooler (IPv4; supports prepared
  statements). Credentials live only in the git-ignored `.env` (template `.env.example`), never in
  code, `application.yml`, docs or commits. Do not use the transaction pooler (port 6543).
- Tests MUST run on `TestcontainersConfiguration` (a throwaway PostgreSQL 17), never on Supabase:
  tests create and truncate data.
- Double booking MUST be prevented inside the database with `EXCLUDE USING gist` constraints
  (`btree_gist` is available on Supabase); application-side checks are only an optimisation. A
  booking that loses the race retries in a new transaction.
- Keep scheduling rules (time ranges, allocation, slot finding) in a pure domain package with no
  Spring or JPA, so they stay unit-testable.
- Schema changes are new Flyway migrations (`V2__...` onwards); never edit a migration that has been
  applied anywhere. `ddl-auto` stays `validate`.
- Every table lives in schema `scheduler`, never `public`: Supabase exposes `public` to anonymous
  callers through its Data API. Extensions go in schema `extensions`. V1 already revokes the
  `anon`/`authenticated` roles' default privileges, so new tables need no extra grants or RLS.
- Demo data lives only in `src/main/resources/db/demo/R__demo_data.sql`, a repeatable migration that
  inserts only when `dealership` is empty. Never put demo rows in `db/migration` or in a versioned
  migration. The application loads both locations; tests load `db/migration` only.
- Database tests extend `DatabaseIntegrationTest` (one shared context and container, tables
  truncated before each test) and are named `*IntegrationTest`; Surefire runs them with
  `./mvnw test`. Assert database rejections by SQLSTATE and constraint name (`SqlAssertions`).
- Store `Instant` / `timestamptz`; convert to the dealership's zone only at the API edge.
- Errors are RFC 9457 problem+json with a stable `code`; add new codes to the relevant spec.
- TDD applies to new work. Do not delete or rewrite existing, tested code just to redo it test-first.
- Log non-trivial AI decisions and verification steps in `docs/AI_LOG.md`; "AI Engineering &
  Verification" is a scored criterion of the assessment.

Each agent folder has an `archive/` subfolder holding configuration copied from an unrelated project
(StudyHub: Android, Kotlin, Supabase). Agents do not load it; never apply its rules here.
