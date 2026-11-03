# The reference executor's answer to an operation that reads the request (design D18 of
# define-executor-contract): `start` and `poll` take the request on standard input and write the
# result over the file GNOMISH_RESULT_FILE names; `describe` reaches this step only from the fake. The answers are sections of ../data, chosen here, never written as shell text:
#   start   with settings `"defer":true`: `pending` with its key, otherwise the role's answer —
#           `transform` completed, `verdict` an empty SARIF log, `decision` a `select` of the first
#           option; a request in a role the describe does not list is refused (exit 2, a line on
#           stderr, no result)
#   poll    the role's answer: the deferred run is finished; a poll whose GNOMISH_PENDING_KEY is not
#           the key the start returned (`reference-deferred`) is refused (exit 2, a line on stderr,
#           no result)
#
# The misbehaving fake: BROKEN, which the [break] preset exports as its link's name `break-<case>`,
# names the one conformance case to break, and only that case's run breaks — the kit runs each case
# in a workspace named after it, so a start breaks when its request's workspace ends in `/<case>`.
# The twelve cases are the conformance kit's (task 8.2 of define-executor-contract), which owns the
# names; this list follows them. Kept in sync with
# bootstrap/src/main/java/com/github/oinsio/gnomish/app/conformance/KitCase.java: the names below and
# the BROKEN switch are exactly its cases (KitCaseScriptIdentitySpec):
#   describe                the describe answer carries an enforcement level no schema accepts
#   settings-schema         the describe answer's settings schema refers to a remote document
#   transform               the start writes no result
#   verdict                 the start answers completed with no verdict
#   decision                the start chooses a value that is not one of the options
#   unknown-role            a start in a role the describe does not list answers completed
#   oversized-output        the result file is larger than the factory's result cap (1 MiB)
#   stderr-noise            the result goes to stderr instead of the result file
#   non-json-stdout         the result goes to stdout instead of the result file
#   pending-poll            the poll writes no result
#   unknown-request-fields  the start refuses a request carrying the kit's probe field (exit 2, a
#                           line on stderr) and answers one without it as the reference does
#   malformed-answer        the start answers a decision request with one option
# Any other name is a broken preset: exit 97.
while [ "${1:-}" = -c ] && [ $# -ge 2 ]; do
    shift 2
done
operation=$1
steps=$(dirname "$0")
data="$steps/../data"

section() {
    awk -v head="[$2]" '$0 == head { on = 1; next } on && /^\[[^]]+\]$/ { exit } on && $0 != "" { print }' "$data/$1"
}

broken=
if [ -n "${BROKEN:-}" ]; then
    broken=${BROKEN#break-}
    case "$broken" in
        describe | settings-schema | transform | verdict | decision | unknown-role | oversized-output | \
            stderr-noise | non-json-stdout | pending-poll | unknown-request-fields | malformed-answer) ;;
        *)
            echo "stand-in: '$BROKEN' names no conformance case (steps/executor-run.sh)" >&2
            exit 97
            ;;
    esac
fi

if [ "$operation" = describe ]; then
    if grep -q -x -F "[break-$broken]" "$data/executor-describe"; then
        section executor-describe "break-$broken" > "$GNOMISH_RESULT_FILE"
    else
        section executor-describe reference > "$GNOMISH_RESULT_FILE"
    fi
    exit 0
fi

request=$(cat)
workspace=$(printf '%s' "$request" | grep -o '"workspace":{"path":"[^"]*"' | head -n 1)
workspace=${workspace%\"}
running=${workspace##*/}
role=$(printf '%s' "$request" | grep -o '"role":"[a-z]*"' | head -n 1 | cut -d '"' -f 4)
[ "$running" = "$broken" ] || broken=

if [ "$operation" = poll ] && [ "$broken" = pending-poll ]; then
    exit 0
fi
if [ "$operation" = poll ] && [ "${GNOMISH_PENDING_KEY:-}" != reference-deferred ]; then
    echo "executor: no pending run has the key '${GNOMISH_PENDING_KEY:-}'" >&2
    exit 2
fi
if [ "$broken" = unknown-request-fields ] && ! printf '%s' "$request" | grep -q '"x-gnomish-conformance-probe"'; then
    broken=
fi
case "$broken" in
    unknown-request-fields)
        echo 'executor: the request carries a field this executor does not know' >&2
        exit 2
        ;;
    transform) exit 0 ;;
    verdict) section executor-result transform > "$GNOMISH_RESULT_FILE"; exit 0 ;;
    malformed-answer) section executor-result malformed-answer > "$GNOMISH_RESULT_FILE"; exit 0 ;;
    oversized-output) OVERSIZED_BYTES=1100000 sh "$steps/oversized-result.sh"; exit 0 ;;
esac

if [ "$operation" = start ] && printf '%s' "$request" | grep -q '"defer":true'; then
    answer=$(section executor-result pending)
else
    case "$role" in
        transform) answer=$(section executor-result transform) ;;
        verdict) answer=$(section executor-result verdict) ;;
        decision)
            chosen=$(printf '%s' "$request" | grep -oE '"options":\["([^"\\]|\\.)*"' | head -n 1)
            chosen=${chosen#\"options\":\[\"}
            chosen=${chosen%\"}
            [ "$broken" = decision ] && chosen=not-an-option
            answer=$(section executor-result select)
            answer="${answer%%@chosen*}$chosen${answer#*@chosen}"
            ;;
        *)
            if [ "$broken" != unknown-role ]; then
                echo 'executor: the request names no role this executor answers' >&2
                exit 2
            fi
            answer=$(section executor-result transform)
            ;;
    esac
fi
case "$broken" in
    stderr-noise) printf '%s\n' "$answer" >&2 ;;
    non-json-stdout) printf '%s\n' "$answer" ;;
    *) printf '%s\n' "$answer" > "$GNOMISH_RESULT_FILE" ;;
esac
