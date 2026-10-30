# Project rules (Antigravity)

Read `AGENTS.md` at the repository root at the start of every task; it is the source of truth for this
project. Essentials, in case it has not been read yet:

- Keyloop Scenario A backend: Java 21, Spring Boot 4.1.2, PostgreSQL 17, Flyway, Testcontainers 2.
  This is a web backend, not a mobile app.
- Behaviour changes go through OpenSpec: `/opsx-explore`, `/opsx-propose`, `/opsx-apply`,
  `/opsx-verify`, `/opsx-archive` (workflows in `.agent/workflows/`). `openspec/specs/` is the source
  of truth for behaviour.
- Never weaken the `EXCLUDE USING gist` overlap constraints that prevent double booking.
- Use the skills in `.agent/skills/` (Spring Boot, Spring Boot 4 testing, Testcontainers, JPA,
  PostgreSQL, API design, TDD, debugging, verification, security review).
- Run `./mvnw test` and show the result before claiming anything works.
- `.agent/archive/` holds configuration from an unrelated Android/Kotlin/Supabase project. Ignore it.
