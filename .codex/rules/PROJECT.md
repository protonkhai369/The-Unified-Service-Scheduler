# Codex project notes

Codex reads `AGENTS.md` at the repository root; that file is the source of truth for this project
(Keyloop Scenario A: Java 21 / Spring Boot 4.1 / PostgreSQL 17 REST backend). Read it before any task.

Codex specifics:

- Skills live in `.codex/skills/`, including `security-review` (Codex has no built-in equivalent).
- OpenSpec commands are custom prompts installed by OpenSpec in `~/.codex/prompts/`:
  `/prompts:opsx-explore`, `/prompts:opsx-propose`, `/prompts:opsx-apply`, `/prompts:opsx-verify`,
  `/prompts:opsx-archive`.
- `.codex/archive/` holds configuration from an unrelated Android/Kotlin/Supabase project. Ignore it.
