# The executor that keeps its pending run in its working copy (the `pending-in-copy` preset; design
# D11 of migrate-verify-to-executor-contract as amended 2026-11-14): a `start` under `"defer":true`
# records the run in `<workspace>/.git/gnomish-pending-run`, and a `poll` answers only when its own
# request's working copy holds that record, refusing the key otherwise (exit 2, a line on stderr
# naming the key and the refused poll, no result). Every answer is the reference executor's
# (steps/executor-run.sh), handed the request this step read.
operation=$1
steps=$(dirname "$0")
request=$(cat)
workspace=$(printf '%s' "$request" | grep -o '"workspace":{"path":"[^"]*"' | head -n 1 | cut -d '"' -f 6)
record="$workspace/.git/gnomish-pending-run"

if [ "$operation" = start ] && printf '%s' "$request" | grep -q '"defer":true'; then
    : > "$record" || { echo "stand-in: cannot record the pending run at $record (steps/pending-in-copy.sh)" >&2; exit 97; }
fi
if [ "$operation" = poll ] && [ ! -e "$record" ]; then
    echo "executor: poll refused: no pending run has the key '${GNOMISH_PENDING_KEY:-}' in this working copy" >&2
    exit 2
fi
printf '%s' "$request" | sh "$steps/executor-run.sh" "$@"
