#!/bin/bash
# Claude Code PostToolUse hook: auto-format edited Java/Groovy files.
# Implements UX3 of add-project-skeleton (design D7, formatting layer 1 of 3:
# agent edit -> this hook; commit -> git pre-commit; CI -> spotlessCheck).
#
# Contract: receives the PostToolUse JSON payload on stdin, extracts the
# edited file path, and applies Spotless to that single file in place via
# the Spotless IDE hook (-PspotlessIdeHook=<absolute path>).
# Always exits 0 — a formatter hiccup must never block the agent's work;
# problems go to stderr and the CI gate remains the backstop.

payload=$(cat)
file_path=$(printf '%s' "$payload" | jq -r '.tool_input.file_path // empty' 2>/dev/null)

case "$file_path" in
    *.java | *.groovy) ;;
    *) exit 0 ;;
esac

[ -f "$file_path" ] || exit 0

project_dir="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"
cd "$project_dir" || exit 0

if ! ./gradlew --quiet spotlessApply "-PspotlessIdeHook=$file_path" 2>/dev/null; then
    echo "format-file hook: Spotless failed for $file_path (CI gate will catch it)" >&2
fi
exit 0
