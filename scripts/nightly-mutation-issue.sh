#!/bin/bash
# Failure report of the nightly whole-tree mutation run (.github/workflows/pitest-nightly.yml).
# Implements FR6 and UX4 of scope-pit-locally (design D7). Kept out of the workflow YAML so
# NightlyMutationIssueScriptSpec in :bootstrap can drive both paths against a stubbed `gh`.
# Compatible with bash 3.x (macOS) and bash 4+ (Linux).
#
# Usage:
#   scripts/nightly-mutation-issue.sh <gradle-log> <run-url> <artifact-name>
#
# Lists the Gradle tasks the log reports as failed, each with the first line of its failure
# message, and reports them on the tracker: a comment on the oldest open issue labelled
# `nightly-mutation` when one exists, a new issue carrying that label otherwise. The label is
# created when the repository does not have it yet. `gh` reads its repository and token from
# the environment (GH_REPO, GH_TOKEN), as every workflow step that calls it does.

set -euo pipefail

readonly LABEL='nightly-mutation'

if [ "$#" -ne 3 ]; then
    echo "usage: $0 <gradle-log> <run-url> <artifact-name>" >&2
    exit 2
fi
readonly LOG="$1" RUN_URL="$2" ARTIFACT="$3"

# Gradle names a failed task twice: `> Task :mod:pitest FAILED` in the progress output and
# `Execution failed for task ':mod:pitest'.` above the failure message, whose first line
# follows as `> …`. The second form carries the reason (the surviving-mutation count of
# `pitestVerifyAllKilled`), so it is preferred; a task seen only in the first form is still
# listed. Each task appears once, in the order the log first names it.
failed_tasks() {
    awk '
        /^Execution failed for task / {
            task = $0
            sub(/^Execution failed for task \047/, "", task)
            sub(/\047\.$/, "", task)
            pending = task
            if (!(task in seen)) { seen[task] = 1; order[++n] = task }
            next
        }
        pending != "" && /^> / {
            if (!(pending in reason)) { reason[pending] = substr($0, 3) }
            pending = ""
            next
        }
        /^> Task :[^ ]+ FAILED$/ {
            task = $3
            if (!(task in seen)) { seen[task] = 1; order[++n] = task }
        }
        END {
            for (i = 1; i <= n; i++) {
                line = "- `" order[i] "`"
                if (order[i] in reason) { line = line ": " reason[order[i]] }
                print line
            }
        }
    ' "$LOG"
}

body() {
    local tasks
    tasks="$(failed_tasks)"
    if [ -z "$tasks" ]; then
        tasks='- no failed Gradle task was found in the log; the run failed before or outside Gradle'
    fi
    cat <<EOF
The nightly whole-tree mutation run (\`./gradlew check -PpitScope=all\` on \`main\`) failed.

Failed tasks:
${tasks}

Run: ${RUN_URL}
PIT reports: the \`${ARTIFACT}\` artifact of that run.

After fixing, dispatch the \`PIT nightly\` workflow by hand to confirm, then close this issue.
EOF
}

if [ -z "$(gh label list --search "$LABEL" --json name --jq ".[] | select(.name == \"${LABEL}\") | .name")" ]; then
    gh label create "$LABEL" --description 'The nightly whole-tree mutation run failed' --color B60205
fi

open_issue="$(gh issue list --label "$LABEL" --state open --json number --jq 'sort_by(.number) | .[0].number // empty')"
if [ -n "$open_issue" ]; then
    body | gh issue comment "$open_issue" --body-file -
else
    body | gh issue create --title 'Nightly mutation run failed' --label "$LABEL" --body-file -
fi
