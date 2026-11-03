#!/usr/bin/env bash
# The usage-report stage's whole work: posts what `gnomish usage --markdown` prints for this
# task as one comment on its open pull request, created on the first run and edited on every
# later one (the comment carries a marker line), so a retried round never posts twice. Every
# figure is the factory's own: the command folds the rounds recorded on the task branch and
# checks the fold against the totals the tip records; this script adds nothing up.
#
# Usage, from the task's worktree:
#   .gnomish/stages/usage-report/report.sh            render and post
#   .gnomish/stages/usage-report/report.sh --print    render to stdout only
#
# Needs git, gh and the factory launcher of the clone this worktree belongs to
# (.gnomish/factory/gnomish, with its jar): the host binding. A box carries no factory, so the
# stage cannot run there.
#
# Exit: 0 posted (or printed); 2 not on a task branch, or no factory launcher; 3 no open pull
# request from the branch; anything else is a failed command, named on stderr (`gnomish usage`
# among them, which exits non-zero, rendering nothing, when the fold and the tip disagree).
set -euo pipefail

MARKER='<!-- gnomish:usage-report -->'

branch=$(git branch --show-current)
case "$branch" in
  gnomish/*) ;;
  *) echo "not on a task branch (gnomish/<task>): $branch" >&2; exit 2 ;;
esac
# The branch is the sanitized task id under gnomish/, and sanitizing is idempotent, so the
# command finds the same branch and register from it.
task=${branch#gnomish/}

# A task worktree shares the clone's .git, and the clone holds the launcher: the jar is
# git-ignored, so the worktree itself has none.
clone=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")
launcher="$clone/.gnomish/factory/gnomish"
[ -x "$launcher" ] || { echo "no factory launcher at $launcher" >&2; exit 2; }

render() {
  "$launcher" usage "$task" --markdown
}

if [ "${1:-}" = "--print" ]; then
  render
  exit 0
fi

report=$(render)
repo=$(sed -n 's/^[[:space:]]*repo:[[:space:]]*//p' .gnomish/config.yaml | head -1)
# gh's built-in field selection picks one field of each answer; nothing is aggregated.
number=$(gh pr list --repo "$repo" --head "$branch" --base main --state open --json number --jq '.[0].number // empty')
[ -n "$number" ] || { echo "no open pull request from $branch into main" >&2; exit 3; }

body=$(printf '%s\n\n%s\n' "$MARKER" "$report")
existing=$(gh api "repos/$repo/issues/$number/comments" --paginate \
  --jq ".[] | select(.body | startswith(\"$MARKER\")) | .id" | head -1)
if [ -n "$existing" ]; then
  gh api --method PATCH "repos/$repo/issues/comments/$existing" -f body="$body" --jq .html_url
else
  gh api --method POST "repos/$repo/issues/$number/comments" -f body="$body" --jq .html_url
fi
