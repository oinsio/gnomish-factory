# In-box verdict program for the fresh-box kill windows of task 5.3 of
# migrate-verify-to-executor-contract (design D11 as amended 2026-11-14) — see
# FakeAgentSandboxImage.ensureBuiltWithVerdictProgram. Run as `sh <this file> <operation>`, so it needs
# no interpreter line. It keeps its run in the key, never in its box, which every request gets anew:
#   describe  the reference executor's describe (/opt/gnomish-fake/judge-describe.json)
#   start     pending under `start-<HEAD>`
#   poll      `start-*` → pending under `poll-<HEAD>`, a rotated key; `poll-*` → the verdict, an empty
#             SARIF log, every time it is asked; any other key is refused (exit 2)
# so the keys the factory records name the commit each request's working copy stood on.
head=$(git rev-parse HEAD 2>/dev/null)
case "$1" in
    describe)
        cat /opt/gnomish-fake/judge-describe.json > "$GNOMISH_RESULT_FILE"
        ;;
    start)
        printf '{"status":"pending","pendingKey":"start-%s","pollAfterMillis":1000,"usage":{"provenance":"ABSENT"}}\n' \
            "$head" > "$GNOMISH_RESULT_FILE"
        ;;
    poll)
        case "${GNOMISH_PENDING_KEY:-}" in
            start-*)
                printf '{"status":"pending","pendingKey":"poll-%s","pollAfterMillis":1000,"usage":{"provenance":"ABSENT"}}\n' \
                    "$head" > "$GNOMISH_RESULT_FILE"
                ;;
            poll-*)
                printf '%s\n' '{"status":"completed","answer":{"kind":"verdict","sarif":{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"judge-bot"}},"results":[]}]}},"usage":{"provenance":"ABSENT"}}' \
                    > "$GNOMISH_RESULT_FILE"
                ;;
            *)
                echo "judge-bot: no pending run has the key '${GNOMISH_PENDING_KEY:-}'" >&2
                exit 2
                ;;
        esac
        ;;
    *)
        echo "judge-bot: no operation '$1'" >&2
        exit 2
        ;;
esac
