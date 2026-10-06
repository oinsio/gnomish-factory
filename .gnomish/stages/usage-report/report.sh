#!/usr/bin/env bash
# The usage-report stage's whole work: renders the task's wall time and tokens per stage and
# per model from `gnomish usage --json` - the factory's own reconstruction of the task branch's
# round history, so no number is estimated by a gnome - and writes it as one comment on the
# task's open pull request, created on the first run and edited on every later one (the
# comment carries a marker line), so a retried round never posts twice.
#
# Usage, from the task's worktree:
#   .gnomish/stages/usage-report/report.sh            render and post
#   .gnomish/stages/usage-report/report.sh --print    render to stdout only
#
# Exit: 0 posted (or printed); 2 not on a task branch or no factory jar; 3 no open pull
# request from the branch; anything else is a failed command, named on stderr.
set -euo pipefail

MARKER='<!-- gnomish:usage-report -->'

branch=$(git branch --show-current)
case "$branch" in
  gnomish/*) task=${branch#gnomish/} ;;
  *) echo "not on a task branch (gnomish/<task>): $branch" >&2; exit 2 ;;
esac

# The jar is the clone's (git-ignored, so absent from a worktree), and `usage` reads the task
# branch from the clone that owns it: the worktree's common git directory.
clone=$(cd "$(git rev-parse --git-common-dir)/.." && pwd)
jar=${GNOMISH_JAR:-$clone/.gnomish/factory/gnomish.jar}
[ -f "$jar" ] || { echo "factory jar not found: $jar" >&2; exit 2; }

render() {
raw=$(java -jar "$jar" usage --dir="$clone" "$task" --json)
# Spring Boot prints its banner on stdout ahead of the document.
json=$(printf '%s\n' "$raw" | sed -n '/^{/,$p')

printf '%s\n' "$json" | jq -r --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
  def dur: (. / 1000 | floor) as $s
    | if $s >= 3600 then "\($s / 3600 | floor) h \(($s % 3600) / 60 | floor) min"
      elif $s >= 60 then "\($s / 60 | floor) min \($s % 60) s"
      else "\($s) s" end;
  def num: if . >= 1000000 then "\(. / 100000 | floor / 10)M"
    elif . >= 1000 then "\(. / 100 | floor / 10)k"
    else tostring end;
  def add_tokens(a; b): reduce ((a | keys_unsorted) + (b | keys_unsorted) | unique)[] as $k
    ({}; .[$k] = {
      input: ((a[$k].input // 0) + (b[$k].input // 0)),
      output: ((a[$k].output // 0) + (b[$k].output // 0)),
      cacheCreation: ((a[$k].cacheCreation // 0) + (b[$k].cacheCreation // 0)),
      cacheRead: ((a[$k].cacheRead // 0) + (b[$k].cacheRead // 0))});
  def row_tokens: reduce (.judgeUsage.perVote[]?.tokensByModel) as $v
    ((.executorUsage.tokensByModel // {}); add_tokens(.; $v));
  def tokens_total: [.[] | .input + .output + .cacheCreation + .cacheRead] | add // 0;

  .rows as $rows
  | ($rows | map(.stage) | reduce .[] as $s ([]; if index([$s]) then . else . + [$s] end)) as $stages
  | ($stages | map(. as $s | $rows | map(select(.stage == $s)) | {
      stage: $s,
      rounds: length,
      agent: (map(.executorUsage.wallMillis // 0) | add),
      checks: (map([.checks[]?.durationMillis // 0] | add // 0) | add),
      tokens: (reduce (.[] | row_tokens) as $t ({}; add_tokens(.; $t)))
    })) as $per
  | ($per | reduce .[].tokens as $t ({}; add_tokens(.; $t))) as $total
  | ($rows | map(.startedAt) | min) as $start
  | (($now | fromdateiso8601) - ($start | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601)) as $elapsed
  | "## Factory usage",
    "",
    "Elapsed from the first round to delivery: \($elapsed * 1000 | dur), time parked for a human included; agent time \($per | map(.agent) | add | dur), verification time \($per | map(.checks) | add | dur).",
    "",
    "| Stage | Rounds | Agent time | Checks time | Tokens |",
    "|-------|-------:|-----------:|------------:|-------:|",
    ($per[] | "| \(.stage) | \(.rounds) | \(.agent | dur) | \(.checks | dur) | \(.tokens | tokens_total | num) |"),
    "",
    "| Model | Input | Output | Cache write | Cache read | Total |",
    "|-------|------:|-------:|------------:|-----------:|------:|",
    ($total | to_entries[] | "| \(.key) | \(.value.input | num) | \(.value.output | num) | \(.value.cacheCreation | num) | \(.value.cacheRead | num) | \([.value] | tokens_total | num) |"),
    "",
    "Tokens include the judges. Every stage up to and including deliver is counted; the round posting this report is not."
'
}

if [ "${1:-}" = "--print" ]; then
  render
  exit 0
fi

number=$(gh pr list --head "$branch" --base main --state open --json number --jq '.[0].number // empty')
[ -n "$number" ] || { echo "no open pull request from $branch into main" >&2; exit 3; }

body=$(printf '%s\n\n%s\n' "$MARKER" "$(render)")
existing=$(gh api "repos/{owner}/{repo}/issues/$number/comments" --paginate \
  --jq ".[] | select(.body | startswith(\"$MARKER\")) | .id" | head -1)
if [ -n "$existing" ]; then
  gh api --method PATCH "repos/{owner}/{repo}/issues/comments/$existing" -f body="$body" --jq .html_url
else
  gh api --method POST "repos/{owner}/{repo}/issues/$number/comments" -f body="$body" --jq .html_url
fi
