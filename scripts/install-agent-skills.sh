#!/usr/bin/env bash
# Installs this project's AI-agent toolkit into a target project, for every agent in use:
#   - curated, reviewed skills from well-known MIT-licensed repositories, pinned to exact commits
#   - OpenSpec (spec-driven development) skills and slash commands / workflows
#
# Usage:  scripts/install-agent-skills.sh [target-dir] [tools]
#         target-dir  default: current directory
#         tools       comma-separated: claude, codex, antigravity
#                     default: every agent whose folder already exists (.claude, .codex, .agent),
#                     or claude if none does
# Needs:  bash, curl, and the OpenSpec CLI (npm install -g @fission-ai/openspec)
#
# Skills are fetched by commit SHA, never from a moving branch, so a re-run installs exactly the
# content that was reviewed. To upgrade: bump a SHA, re-run, and review the diff before committing.
# See docs/AGENT_SKILLS.md for why each skill was chosen.
set -euo pipefail

TARGET="${1:-.}"
TOOLS="${2:-}"

if [ -z "$TOOLS" ]; then
    detected=()
    [ -d "$TARGET/.claude" ] && detected+=(claude)
    [ -d "$TARGET/.codex" ] && detected+=(codex)
    [ -d "$TARGET/.agent" ] && detected+=(antigravity)
    [ ${#detected[@]} -eq 0 ] && detected=(claude)
    TOOLS=$(IFS=,; echo "${detected[*]}")
fi

skills_dir_for() {
    case "$1" in
        claude) echo "$TARGET/.claude/skills" ;;
        codex) echo "$TARGET/.codex/skills" ;;
        antigravity) echo "$TARGET/.agent/skills" ;;
        *) echo "Unsupported tool: $1 (expected claude, codex or antigravity)" >&2; exit 1 ;;
    esac
}

IFS=, read -r -a TOOL_LIST <<< "$TOOLS"
for tool in "${TOOL_LIST[@]}"; do skills_dir_for "$tool" >/dev/null; done

AWESOME_COPILOT="github/awesome-copilot@b6483db44fb602654bca6a097185fcaddb305f97"
SUPERPOWERS="obra/superpowers@2ce8aa15d05bd57213e6f746f953aafb7ded7a8b"
WSHOBSON="wshobson/agents@0874a27b17a22e2b7c2e2bcd725768e0803cad06"

STAGING=$(mktemp -d)
trap 'rm -rf "$STAGING"' EXIT

# fetch_skill <repo@sha> <path-in-repo> <skill-name> <file>...   (downloads into $STAGING once)
fetch_skill() {
    local repo="${1%@*}" sha="${1#*@}" src="$2" name="$3"
    shift 3
    local file
    for file in "$@"; do
        mkdir -p "$(dirname "$STAGING/$name/$file")"
        curl -fsSL "https://raw.githubusercontent.com/$repo/$sha/$src/$file" -o "$STAGING/$name/$file"
    done
    curl -fsSL "https://raw.githubusercontent.com/$repo/$sha/LICENSE" -o "$STAGING/$name/LICENSE"
    echo "  fetched $name  ($repo@${sha:0:7})"
}

# deploy_skill <skill-name> <tool>...   (copies a staged skill into each tool's skills folder)
deploy_skill() {
    local name="$1" tool dir
    shift
    for tool in "$@"; do
        dir=$(skills_dir_for "$tool")
        mkdir -p "$dir"
        rm -rf "${dir:?}/$name"
        cp -r "$STAGING/$name" "$dir/$name"
    done
}

echo "Fetching curated skills"
fetch_skill "$AWESOME_COPILOT" skills/java-springboot java-springboot SKILL.md
fetch_skill "$AWESOME_COPILOT" skills/spring-boot-testing spring-boot-testing SKILL.md \
    references/assertj-basics.md references/assertj-collections.md references/context-caching.md \
    references/datajpatest.md references/instancio.md references/mockitobean.md \
    references/mockmvc-classic.md references/mockmvc-tester.md references/restclienttest.md \
    references/resttestclient.md references/sb4-migration.md references/test-slices-overview.md \
    references/testcontainers-jdbc.md references/webmvctest.md
fetch_skill "$AWESOME_COPILOT" skills/java-testcontainers java-testcontainers SKILL.md \
    references/containers-and-waiting.md references/environment-ci-troubleshooting.md \
    references/lifecycle-and-reuse.md
fetch_skill "$AWESOME_COPILOT" skills/java-hibernate-jpa java-hibernate-jpa SKILL.md \
    references/associations-and-cascade.md references/fetching-and-n-plus-one.md \
    references/identifiers-and-entity-design.md references/locking-transactions-caching.md \
    references/queries-pagination-batching.md references/tooling-and-versions.md
fetch_skill "$AWESOME_COPILOT" skills/multi-stage-dockerfile multi-stage-dockerfile SKILL.md
fetch_skill "$AWESOME_COPILOT" skills/security-review security-review SKILL.md \
    references/language-patterns.md references/report-format.md references/secret-patterns.md \
    references/vuln-categories.md references/vulnerable-packages.md

fetch_skill "$SUPERPOWERS" skills/test-driven-development test-driven-development \
    SKILL.md writing-good-tests.md
fetch_skill "$SUPERPOWERS" skills/systematic-debugging systematic-debugging SKILL.md \
    condition-based-waiting-example.ts condition-based-waiting.md defense-in-depth.md \
    find-polluter.sh root-cause-tracing.md
fetch_skill "$SUPERPOWERS" skills/verification-before-completion verification-before-completion SKILL.md

fetch_skill "$WSHOBSON" plugins/database-design/skills/postgresql-table-design postgresql-table-design \
    SKILL.md references/details.md
fetch_skill "$WSHOBSON" plugins/backend-development/skills/api-design-principles api-design-principles \
    SKILL.md assets/api-design-checklist.md assets/rest-api-template.py references/details.md \
    references/graphql-schema-design.md references/rest-best-practices.md

# superpowers is normally installed as a Claude Code plugin, where its skills are namespaced
# "superpowers:<name>". Installed as project skills they are plain "<name>".
sed -i.bak 's/`superpowers:test-driven-development`/`test-driven-development`/; s/`superpowers:verification-before-completion`/`verification-before-completion`/' \
    "$STAGING/systematic-debugging/SKILL.md"
rm -f "$STAGING/systematic-debugging/SKILL.md.bak"

echo "Installing skills for: ${TOOL_LIST[*]}"
for skill in java-springboot spring-boot-testing java-testcontainers java-hibernate-jpa \
             multi-stage-dockerfile test-driven-development systematic-debugging \
             verification-before-completion postgresql-table-design api-design-principles; do
    deploy_skill "$skill" "${TOOL_LIST[@]}"
done
# Claude Code ships a built-in /security-review; a project skill with the same name would shadow it.
for tool in "${TOOL_LIST[@]}"; do
    [ "$tool" != claude ] && deploy_skill security-review "$tool"
done

echo "Setting up OpenSpec"
if ! command -v openspec >/dev/null 2>&1; then
    echo "  OpenSpec CLI not found: npm install -g @fission-ai/openspec, then re-run" >&2
    exit 1
fi
# Adds skills and commands for each tool; an existing openspec/config.yaml and specs are kept.
# Note: for Codex, OpenSpec writes its /opsx-* prompts to the user-level $CODEX_HOME/prompts
# (default ~/.codex/prompts), because Codex only reads custom prompts from there.
openspec init --tools "$TOOLS" "$TARGET"

echo "Done. Restart each agent (or the IDE) so new skills and OpenSpec commands are picked up."
