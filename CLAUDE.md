@AGENTS.md

## Claude Code specifics

- OpenSpec commands are `/opsx:*` (in `.claude/commands/opsx/`); skills live in `.claude/skills/`.
- Use the built-in `/security-review` and `/code-review` commands; there is deliberately no
  `security-review` project skill here, because it would shadow the built-in one.
