#!/bin/sh
# A sample verdict program for the Gnomish Factory executor contract (FR8 of
# migrate-verify-to-executor-contract): what a third party writes to add a check to a stage. It
# depends on nothing of the factory — no jar, no library, no build — only on a POSIX shell, git,
# and the contract: the operation as the last argument (`describe`, `start`, `poll`), the request
# as JSON on standard input, the result written to the file GNOMISH_RESULT_FILE names, and a poll's
# key in GNOMISH_PENDING_KEY. Standard output and standard error are logs, never the result.
#
# The check: the attempt commit under verification — HEAD of the working copy the request names —
# must not carry a file named DO-NOT-MERGE at its root. Such a file is an `error` result, so the
# check fails; otherwise the run passes with one `note` naming the commit it judged (the engine,
# not this program, derives pass or fail from the result levels against the check's threshold).
#
# With the setting `defer: true` it behaves like a check of an external system: `start` answers
# `pending`, and `poll` answers the verdict. The pending key carries everything the poll needs,
# so a poll from any working copy, on any factory instance, answers alike, and polling the key of
# a run that already ended answers that run again (the idempotent poll the contract requires).
#
# Verify it, from this directory: `gnomish executor verify ./sample-verdict.sh
# --pending-settings='{"defer":true}'`. A real program would parse the request with a JSON
# library; the pattern matches below rely on the factory's compact JSON and keep the sample short.
set -eu

operation=${1:-}
result=${GNOMISH_RESULT_FILE:?the factory names the result file in GNOMISH_RESULT_FILE}
usage='"usage":{"provenance":"REPORTED","models":{}}'

if [ "$operation" = describe ]; then
    printf '%s\n' '{"protocolVersion":1,"version":"1","roles":["verdict"],"capabilities":["poll"],'\
'"settingsSchema":{"type":"object","properties":{"defer":{"type":"boolean"}}},"credentials":[],'\
'"egress":[],"policyDisablingFlags":[],"enforcementLevel":"PROMPT_ONLY","reportsUsage":true}' > "$result"
    exit 0
fi
if [ "$operation" != start ] && [ "$operation" != poll ]; then
    echo "sample-verdict: unknown operation '$operation'" >&2
    exit 2
fi

request=$(cat)
if ! printf '%s' "$request" | grep -q '"role":"verdict"'; then
    echo 'sample-verdict: this program answers the role verdict only' >&2
    exit 2
fi

# The verdict of a judged run as a SARIF log: its outcome (`pass` or `fail`) and the commit judged.
verdict() {
    note="{\"ruleId\":\"sample/judged-commit\",\"level\":\"note\",\"message\":{\"text\":\"judged commit $2\"}}"
    results=$note
    if [ "$1" = fail ]; then
        results="$note,{\"ruleId\":\"sample/do-not-merge\",\"level\":\"error\",\"message\":{\"text\":\"commit $2 carries a DO-NOT-MERGE file\"}}"
    fi
    printf '{"status":"completed","answer":{"kind":"verdict","sarif":{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"sample-verdict"}},"results":[%s]}]}},%s}\n' \
        "$results" "$usage" > "$result"
}

if [ "$operation" = poll ]; then
    key=${GNOMISH_PENDING_KEY:-}
    case "$key" in
        sample:pass:* | sample:fail:*) ;;
        *)
            echo "sample-verdict: no run has the key '$key'" >&2
            exit 2
            ;;
    esac
    rest=${key#sample:}
    verdict "${rest%%:*}" "${rest#*:}"
    exit 0
fi

# start: judge HEAD of the working copy the request names.
workspace=$(printf '%s' "$request" | sed -n 's/.*"workspace":{"path":"\([^"]*\)".*/\1/p')
if [ -z "$workspace" ]; then
    echo 'sample-verdict: the request names no working copy' >&2
    exit 2
fi
commit=$(git -C "$workspace" rev-parse --verify --quiet HEAD || true)
outcome=pass
if [ -n "$commit" ] && git -C "$workspace" cat-file -e "HEAD:DO-NOT-MERGE" 2>/dev/null; then
    outcome=fail
fi
judged=${commit:-none}
echo "sample-verdict: judged commit $judged: $outcome"

if printf '%s' "$request" | grep -q '"defer"[[:space:]]*:[[:space:]]*true'; then
    printf '{"status":"pending","pendingKey":"sample:%s:%s",%s}\n' "$outcome" "$judged" "$usage" > "$result"
else
    verdict "$outcome" "$judged"
fi
