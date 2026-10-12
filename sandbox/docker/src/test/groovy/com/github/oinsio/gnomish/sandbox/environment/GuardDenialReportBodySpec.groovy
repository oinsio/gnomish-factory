package com.github.oinsio.gnomish.sandbox.environment

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.status.Outcome
import com.github.oinsio.gnomish.status.StatusReport
import com.github.oinsio.gnomish.status.json.StatusReportJsonMapper
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * NFR-S1 of fix-denial-report-attachment; NFR-S1 of fix-denial-attribution-durability: a denied
 * request's own payload never reaches {@code status.json}. The guard line here is hostile — it
 * carries a body and a query string the addon would never print — and it travels the whole real
 * path: {@link GuardDenialLog} parses it, the denial rides an attempt or a {@code cannotExecute}
 * escalation, and {@link StatusReportJsonMapper} renders the report. The entry that comes out is
 * the three metadata fields of a finding, and the payload is nowhere in the document.
 */
class GuardDenialReportBodySpec extends Specification {

    private static final String PAYLOAD = 'stolen-payload-0x5f3759df'

    private static final String GUARD_LINE = EgressGuardConfig.DENY_MARKER + '{"kind":"http","host":"paste.example.com",' +
    '"port":443,"method":"POST","path":"/upload?token=' + PAYLOAD + '","body":"' + PAYLOAD + '"}\n'

    private static final TaskContext CONTEXT =
    new TaskContext('BODY-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), [])

    private static Map rendered(StatusReport report) {
        new ObjectMapper().readValue(new StatusReportJsonMapper().serialize(report), Map)
    }

    private static List denials() {
        GuardDenialLog.denials('gnomish-BODY-1', null, GUARD_LINE)
    }

    // Scenario: observability/status-report / An attempt denial carries no request body
    def "NFR-S1: an attempt's denial entry is the finding's metadata, never the request body"() {
        given: 'a passing round that recorded the hostile denial'
        def attempt = new AttemptRecord(0, AttemptRecord.Result.PASSED, Instant.EPOCH, [], ExecutorUsage.none(),
        JudgeUsage.none(), denials(), Stop.none())
        def state = new TaskState(new Position.AtStage('implement'), 1, [attempt], ExecutorUsage.none())

        when:
        def document = rendered(StatusReport.build(CONTEXT, state, null, null))

        then: 'the entry holds the three metadata fields only, naming the destination'
        def entry = document.currentStage.attempts.first().denials.first()
        entry.keySet() == [
            'message',
            'location',
            'details'
        ] as Set
        entry.location == 'paste.example.com:443/upload'

        and: 'the payload appears nowhere in the document'
        !new ObjectMapper().writeValueAsString(document).contains(PAYLOAD)
    }

    // Scenario: observability/status-report / An escalation denial carries no request body
    def "NFR-S1: a cannotExecute escalation's denial entry is the finding's metadata, never the request body"() {
        given: 'a round that could not execute, its hostile denial riding the escalation'
        def escalation = new EscalationReport.CannotExecute(UntrustedText.subprocess('round timed out'), denials())

        when:
        def document = rendered(StatusReport.build(CONTEXT, TaskState.atStageStart('implement'), escalation,
                new Outcome.Escalated(escalation)))

        then: 'the entry holds the three metadata fields only, naming the destination'
        def entry = document.lastEscalation.denials.first()
        entry.keySet() == [
            'message',
            'location',
            'details'
        ] as Set
        entry.message == 'egress denied: paste.example.com:443'

        and: 'the payload appears nowhere in the document'
        !new ObjectMapper().writeValueAsString(document).contains(PAYLOAD)
    }
}
