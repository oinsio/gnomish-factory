package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import spock.lang.Specification

/**
 * FR13, D12, UX3 of add-tracker-port (task 5.8): a fresh {@code Escalated} outcome must end the
 * take run identically with or without a TTY — park the task with its report, then exit with the
 * return-path message. {@link TakeEngineExecution} previously fell through to {@code
 * TakeOutcomeMapper}'s placeholder mapping without ever calling {@code tracker.park}; {@link
 * TakeEscalationExit} closes that gap.
 */
class TakeEscalationExitSpec extends Specification {

    static final TaskRef REF = new TaskRef('PROJ-1')
    static final TaskState STATE = TaskState.atStageStart('build')

    Tracker tracker = Mock()

    // FR13, D12: AttemptsExhausted needs a human decision — ESCALATION reason, reply-and-return message.
    def "exit parks AttemptsExhausted escalation as ESCALATION with a reply-and-return report"() {
        given:
        def escalated = new TaskOutcome.Escalated(STATE, new EscalationReport.AttemptsExhausted(3))

        when:
        def result = TakeEscalationExit.exit(escalated, tracker, REF)

        then:
        1 * tracker.park(REF, ParkReason.ESCALATION, { String report ->
            report.contains('Attempt limit (3) reached') &&
            report.toLowerCase().contains('reply') &&
            report.toLowerCase().contains('ready')
        })

        and:
        result instanceof TakeResult.AwaitingHuman
        def awaiting = result as TakeResult.AwaitingHuman
        awaiting.reason() == ParkReason.ESCALATION
        awaiting.finalState() == STATE
        awaiting.report().contains('Attempt limit (3) reached')
        awaiting.report().toLowerCase().contains('reply')
    }

    // FR13, D12, UX3: DecisionNeeded's report must also carry the rendered question/options text.
    def "exit parks DecisionNeeded escalation as ESCALATION with the rendered question and a reply-and-return report"() {
        given:
        def report = new EscalationReport.DecisionNeeded('Which approach?', ['A', 'B'])
        def escalated = new TaskOutcome.Escalated(STATE, report)

        when:
        def result = TakeEscalationExit.exit(escalated, tracker, REF)

        then:
        1 * tracker.park(REF, ParkReason.ESCALATION, { String r ->
            r.contains('Which approach?') &&
            r.contains('A') && r.contains('B') &&
            r.toLowerCase().contains('reply') &&
            r.toLowerCase().contains('ready')
        })

        and:
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.ESCALATION
    }

    // FR13, D12, UX3: infra-kind escalations need a fix, not a reply — distinct return-path wording.
    def "exit parks #kind escalation as INFRA with a fix-and-return report, no reply wording"() {
        when:
        def result = TakeEscalationExit.exit(new TaskOutcome.Escalated(STATE, escalationReport), tracker, REF)

        then:
        1 * tracker.park(REF, ParkReason.INFRA, { String r ->
            !r.toLowerCase().contains('reply') &&
            r.toLowerCase().contains('fix') &&
            r.toLowerCase().contains('ready')
        })

        and:
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.INFRA

        where:
        kind               | escalationReport
        'CannotVerify'      | new EscalationReport.CannotVerify(new CheckRef(0, 'command:test'), 'timed out', '')
        'CannotExecute'     | new EscalationReport.CannotExecute('executor crashed')
        'PipelineMismatch'  | new EscalationReport.PipelineMismatch('old-stage')
    }
}
