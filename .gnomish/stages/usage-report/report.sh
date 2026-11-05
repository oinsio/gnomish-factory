#!/usr/bin/env bash
# The usage-report stage's whole work: renders the task's wall time and tokens per stage and
# per model from the task branch's own history - every `gnomish: round <stage>#<n>` commit
# carries .gnomish-task/state.json with that attempt's executor and judge usage and its check
# times, written by the factory, so no number is estimated by a gnome - and writes it as one
# comment on the task's open pull request, created on the first run and edited on every later
# one (the comment carries a marker line), so a retried round never posts twice.
#
# Usage, from the task's worktree:
#   .gnomish/stages/usage-report/report.sh            render and post
#   .gnomish/stages/usage-report/report.sh --print    render to stdout only
#
# Needs only git, jq and gh, so it runs in a box as well as on the host.
#
# Exit: 0 posted (or printed); 2 not on a task branch, no round commits or a state.json
# version this script does not read; 3 no open pull request from the branch; anything else is
# a failed command, named on stderr.
set -euo pipefail

MARKER='<!-- gnomish:usage-report -->'
STATE_FILE='.gnomish-task/state.json'
# The state.json layouts whose attempts[] this script reads. A new version is added here only
# after checking that attempts[].startedAt, checks[].durationMillis, executorUsage and
# judgeUsage.perVote[].tokensByModel still mean the same.
SUPPORTED_STATE_VERSIONS='1'

branch=$(git branch --show-current)
case "$branch" in
  gnomish/*) ;;
  *) echo "not on a task branch (gnomish/<task>): $branch" >&2; exit 2 ;;
esac

# One row per round commit: the attempt whose round number the commit subject names, taken
# from the state.json that commit wrote. A branch that merged a rewritten copy of its own
# history reaches each round commit twice, so rows are unique by stage, round and start time.
# Oldest first, so stages keep pipeline order.
collect_rows() {
  git log --reverse --format='%H %s' HEAD \
    | awk '$2 == "gnomish:" && $3 == "round" { print $1, $4 }' \
    | while read -r sha round_key; do
        git show "$sha:$STATE_FILE" | jq -c \
          --arg stage "${round_key%#*}" --argjson round "${round_key##*#}" '
          .version as $version
          | .attempts[] | select(.round == $round)
          | {stage: $stage, round: $round, stateVersion: $version, startedAt, checks, executorUsage, judgeUsage}'
      done \
    | jq -s '{rows: (unique_by([.stage, .round, .startedAt]) | sort_by(.startedAt))}'
}

rows_json=$(collect_rows)
[ "$(printf '%s' "$rows_json" | jq '.rows | length')" -gt 0 ] \
  || { echo "no gnomish round commits on $branch" >&2; exit 2; }
unsupported=$(printf '%s' "$rows_json" | jq -r --arg supported "$SUPPORTED_STATE_VERSIONS" '
  ($supported | split(" ")) as $known
  | [.rows[].stateVersion | tostring | select(. as $v | $known | index($v) | not)] | unique | join(", ")')
[ -z "$unsupported" ] || {
  echo "$STATE_FILE version $unsupported is not one this script reads ($SUPPORTED_STATE_VERSIONS)" >&2
  exit 2
}

render() {
printf '%s\n' "$rows_json" | jq -r --arg now "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '
  def dur: (. / 1000 | floor) as $s
    | if $s >= 3600 then "\($s / 3600 | floor) h \(($s % 3600) / 60 | floor) min"
      elif $s >= 60 then "\($s / 60 | floor) min \($s % 60) s"
      else "\($s) s" end;
  def num: if . >= 1000000 then "\(. / 100000 | floor / 10)M"
    elif . >= 1000 then "\(. / 100 | floor / 10)k"
    else tostring end;
  def add_tokens($a; $b): reduce (($a | keys_unsorted) + ($b | keys_unsorted) | unique)[] as $k
    ({}; .[$k] = {
      input: (($a[$k].input // 0) + ($b[$k].input // 0)),
      output: (($a[$k].output // 0) + ($b[$k].output // 0)),
      cacheCreation: (($a[$k].cacheCreation // 0) + ($b[$k].cacheCreation // 0)),
      cacheRead: (($a[$k].cacheRead // 0) + ($b[$k].cacheRead // 0))});
  def role_tokens($role): if $role == "executor" then (.executorUsage.tokensByModel // {})
    else reduce (.judgeUsage.perVote[]?.tokensByModel) as $v ({}; add_tokens(.; $v)) end;
  def row_tokens: add_tokens(role_tokens("executor"); role_tokens("judge"));
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
  | ($stages | map(. as $s | $rows | map(select(.stage == $s)) as $stage_rows
      | ("executor", "judge") as $role
      | $stage_rows | reduce (.[] | role_tokens($role)) as $t ({}; add_tokens(.; $t))
      | to_entries[] | {stage: $s, role: $role, model: .key, usage: .value})) as $by_role
  | ($per | reduce .[].tokens as $t ({}; add_tokens(.; $t))) as $total
  | ($rows | map(.startedAt) | min) as $start
  | (($now | fromdateiso8601) - ($start | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601)) as $elapsed
  | ($rows | map(.stateVersion | tostring) | unique | join(", ")) as $versions
  | "## Factory usage",
    "",
    "Elapsed from the first round to delivery: \($elapsed * 1000 | dur), time parked for a human included; agent time \($per | map(.agent) | add | dur), verification time \($per | map(.checks) | add | dur).",
    "",
    "### Time and tokens per stage",
    "",
    "One row per pipeline stage, in run order. Agent time is the executor'\''s wall time, checks time is the verification commands, tokens are the executor'\''s and the judges'\'' together.",
    "",
    "| Stage | Rounds | Agent time | Checks time | Tokens |",
    "|-------|-------:|-----------:|------------:|-------:|",
    ($per[] | "| \(.stage) | \(.rounds) | \(.agent | dur) | \(.checks | dur) | \(.tokens | tokens_total | num) |"),
    "",
    "### Token totals per model",
    "",
    "Every stage and role added up: how many tokens the whole task spent on each model.",
    "",
    "| Model | Input | Output | Cache write | Cache read | Total |",
    "|-------|------:|-------:|------------:|-----------:|------:|",
    ($total | to_entries[] | "| \(.key) | \(.value.input | num) | \(.value.output | num) | \(.value.cacheCreation | num) | \(.value.cacheRead | num) | \([.value] | tokens_total | num) |"),
    "",
    "### Token detail per stage, role and model",
    "",
    "The totals above split by stage and by who spent them: the executor, which does the stage'\''s work, or the judges, which review it.",
    "",
    "| Stage | Role | Model | Input | Output | Cache write | Cache read | Total |",
    "|-------|------|-------|------:|-------:|------------:|-----------:|------:|",
    ($by_role[] | "| \(.stage) | \(.role) | \(.model) | \(.usage.input | num) | \(.usage.output | num) | \(.usage.cacheCreation | num) | \(.usage.cacheRead | num) | \([.usage] | tokens_total | num) |"),
    "",
    "Every stage up to and including deliver is counted; the round posting this report is not.",
    "",
    "Source: `.gnomish-task/state.json` version \($versions), read from \($rows | length) rounds on the task branch."
'
}

if [ "${1:-}" = "--print" ]; then
  render
  exit 0
fi

repo=$(sed -n 's/^[[:space:]]*repo:[[:space:]]*//p' .gnomish/config.yaml | head -1)
number=$(gh pr list --repo "$repo" --head "$branch" --base main --state open --json number --jq '.[0].number // empty')
[ -n "$number" ] || { echo "no open pull request from $branch into main" >&2; exit 3; }

body=$(printf '%s\n\n%s\n' "$MARKER" "$(render)")
existing=$(gh api "repos/$repo/issues/$number/comments" --paginate \
  --jq ".[] | select(.body | startswith(\"$MARKER\")) | .id" | head -1)
if [ -n "$existing" ]; then
  gh api --method PATCH "repos/$repo/issues/comments/$existing" -f body="$body" --jq .html_url
else
  gh api --method POST "repos/$repo/issues/$number/comments" -f body="$body" --jq .html_url
fi
