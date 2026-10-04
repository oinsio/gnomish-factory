#!/bin/sh
# A stand-in for `gh run list`, used by ReleasePreflightScriptSpec: applies the filters it is
# given to the runs in $GH_STUB_RUNS (newest first), as gh does, and logs every call.
printf '%s\n' "$*" >> "$GH_STUB_LOG"
[ -z "${GH_STUB_FAIL:-}" ] || { echo 'HTTP 502' >&2; exit 1; }
[ "$1 $2" = 'run list' ] || { echo "unexpected gh call: $*" >&2; exit 64; }
shift 2
filter='.'
expr='.'
while [ $# -gt 0 ]; do
    case "$1" in
        --workflow) filter="$filter | map(select(.workflow == \"$2\"))" ;;
        --commit) filter="$filter | map(select(.headSha == \"$2\"))" ;;
        --branch) filter="$filter | map(select(.headBranch == \"$2\"))" ;;
        --event) filter="$filter | map(select(.event == \"$2\"))" ;;
        --jq) expr="$2" ;;
        --json|--limit) ;;
        *) echo "unexpected argument: $1" >&2; exit 64 ;;
    esac
    shift 2
done
jq -r "$filter | $expr" "$GH_STUB_RUNS"
