# The SonarQube-shaped reference verdict program of the M3 end-to-end spec of
# migrate-verify-to-executor-contract (task 7.1) — see FakeAgentSandboxImage.ensureBuiltWithSonarGate.
# Run as `sh <this file> <operation>`, so it needs no interpreter line. It is a quality-gate client of
# the SonarQube Web API, against the server whose address the image was installed with
# (/opt/gnomish-sonar/host, port 9000), authenticated by the SONAR_TOKEN the law grants it; it keeps
# its run on the server, never in its box, which every request gets anew:
#   describe  a verdict program that polls, declaring SONAR_TOKEN and the server's address as egress
#   start     submits an analysis of the box's HEAD (`revision`), pending under the server's task id
#   poll      reads the task (sending the box's HEAD too): still running is pending under the same key;
#             SUCCESS reads the quality gate of its analysis, OK a verdict with no result, any other
#             gate status one error result naming it
# A call the server refuses is failed/infrastructure naming the call.
host=$(cat /opt/gnomish-sonar/host)
server="http://$host:9000"
head=$(git rev-parse HEAD 2>/dev/null)

call() {
    curl -sS --fail-with-body --max-time 30 -H "Authorization: Bearer $SONAR_TOKEN" "$@"
}

infrastructure() {
    printf '{"status":"failed","failureClass":"infrastructure","reason":"sonar-gate: %s","usage":{"provenance":"ABSENT"}}\n' \
        "$1" > "$GNOMISH_RESULT_FILE"
    exit 0
}

field() {
    sed -n "s/.*\"$1\": *\"\([^\"]*\)\".*/\1/p"
}

pending() {
    printf '{"status":"pending","pendingKey":"%s","pollAfterMillis":1000,"usage":{"provenance":"ABSENT"}}\n' \
        "$1" > "$GNOMISH_RESULT_FILE"
}

case "$1" in
    describe)
        printf '{"protocolVersion":1,"version":"1","roles":["verdict"],"capabilities":["poll"],"settingsSchema":{},"credentials":["SONAR_TOKEN"],"egress":["%s"],"policyDisablingFlags":[],"enforcementLevel":"PROMPT_ONLY","reportsUsage":false}\n' \
            "$host" > "$GNOMISH_RESULT_FILE"
        ;;
    start)
        task=$(call -X POST "$server/api/ce/submit?projectKey=gnomish&revision=$head" | field taskId)
        [ -n "$task" ] || infrastructure 'the analysis submission was refused'
        pending "$task"
        ;;
    poll)
        key=${GNOMISH_PENDING_KEY:-}
        answer=$(call "$server/api/ce/task?id=$key&revision=$head") || infrastructure "no analysis task '$key'"
        case "$(printf '%s' "$answer" | field status)" in
            PENDING | IN_PROGRESS) pending "$key" ;;
            SUCCESS)
                analysis=$(printf '%s' "$answer" | field analysisId)
                gate=$(call "$server/api/qualitygates/project_status?analysisId=$analysis" |
                    sed -n 's/.*"projectStatus": *{"status": *"\([A-Z]*\)".*/\1/p')
                case "$gate" in
                    '') infrastructure "no quality gate for analysis '$analysis'" ;;
                    OK) results='' ;;
                    *) results=$(printf '{"ruleId":"quality-gate","level":"error","message":{"text":"quality gate %s"}}' "$gate") ;;
                esac
                printf '{"status":"completed","answer":{"kind":"verdict","sarif":{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"sonar-gate"}},"results":[%s]}]}},"usage":{"provenance":"ABSENT"}}\n' \
                    "$results" > "$GNOMISH_RESULT_FILE"
                ;;
            *) infrastructure "analysis task '$key' ended without a result" ;;
        esac
        ;;
    *)
        echo "sonar-gate: no operation '$1'" >&2
        exit 2
        ;;
esac
