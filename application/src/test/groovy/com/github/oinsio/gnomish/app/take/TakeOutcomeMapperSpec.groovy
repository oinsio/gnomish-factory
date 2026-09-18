package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.EscalationResumeDialog
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * TakeOutcomeMapper: maps an engine TaskOutcome to the TakeResult that decides
 * which tracker-port call the take runner makes next (design D3). Covers the
 * three mapper-accepted TaskOutcome variants and, for Escalated, all five
 * EscalationReport kinds and their exact ESCALATION/INFRA split.
 *
 * Implements FR18, D2, D3 of add-tracker-port.
 */
class TakeOutcomeMapperSpec extends Specification {

    private static final TaskState STATE = TaskState.atStageStart('implement')

    // FR18, D3: Completed maps to Delivered, carrying the final state through unchanged
    def "a Completed outcome maps to Delivered"() {
        given: 'a Completed outcome'
        def outcome = new TaskOutcome.Completed(STATE)

        when: 'it is mapped'
        def result = TakeOutcomeMapper.map(outcome)

        then: 'the result is Delivered, carrying the same final state'
        result instanceof TakeResult.Delivered
        (result as TakeResult.Delivered).finalState() == STATE
        !(result as TakeResult.Delivered).summary().isBlank()
    }

    // FR18, D3: Paused maps to AwaitingHuman(CHECKPOINT) — a manual pipeline pause
    def "a Paused outcome maps to AwaitingHuman with CHECKPOINT"() {
        given: 'a Paused outcome naming the stage that passed'
        def outcome = new TaskOutcome.Paused(STATE, 'implement')

        when: 'it is mapped'
        def result = TakeOutcomeMapper.map(outcome)

        then: 'the result parks with CHECKPOINT and mentions the passed stage'
        result instanceof TakeResult.AwaitingHuman
        def awaiting = result as TakeResult.AwaitingHuman
        awaiting.finalState() == STATE
        awaiting.reason() == ParkReason.CHECKPOINT
        awaiting.report().contains('implement')
    }

    // FR18, D3: the five EscalationReport kinds split exactly into ESCALATION vs INFRA
    def "an Escalated outcome maps its report kind to the design D3 park reason"() {
        given: 'an Escalated outcome for one report kind'
        def outcome = new TaskOutcome.Escalated(STATE, report)

        when: 'it is mapped'
        def result = TakeOutcomeMapper.map(outcome)

        then: 'the result parks with the D3-mandated reason'
        result instanceof TakeResult.AwaitingHuman
        def awaiting = result as TakeResult.AwaitingHuman
        awaiting.finalState() == STATE
        awaiting.reason() == expectedReason
        !awaiting.report().isBlank()

        where:
        report || expectedReason
        new EscalationReport.AttemptsExhausted(3) || ParkReason.ESCALATION
        new EscalationReport.DecisionNeeded(UntrustedText.agent('proceed?'), [
            UntrustedText.agent('yes'),
            UntrustedText.agent('no')
        ]) || ParkReason.ESCALATION
        new EscalationReport.CannotVerify(new CheckRef(0, UntrustedText.manifest('tests')), UntrustedText.subprocess('timeout'), UntrustedText.subprocess('')) || ParkReason.INFRA
        new EscalationReport.CannotExecute(UntrustedText.subprocess('executor crashed'), []) || ParkReason.INFRA
        new EscalationReport.PipelineMismatch(UntrustedText.branchDocument('stale-stage')) || ParkReason.INFRA
    }

    // FR7, design D8 of harden-untrusted-text-sinks: the park report is the escalation render, not
    // the record's own toString. A record's toString names its component fields — and for
    // CannotVerify one of those fields is the check's raw output, which reaches a human unfenced
    // and unsanitized that way, past the very fence the renderer puts around it.
    def "the park report carries no EscalationReport record toString shape — #kind"() {
        given:
        def outcome = new TaskOutcome.Escalated(STATE, report)

        when:
        def awaiting = TakeOutcomeMapper.map(outcome) as TakeResult.AwaitingHuman

        then: 'no record rendering anywhere in the text'
        !(awaiting.report() =~ /[A-Za-z]+\[[a-zA-Z]+=/)
        !awaiting.report().startsWith('Escalated: ')

        and: 'it is the renderer\'s own text, word for word'
        awaiting.report() == EscalationResumeDialog.renderEscalation(report)

        where:
        kind | report
        'AttemptsExhausted' | new EscalationReport.AttemptsExhausted(3)
        'DecisionNeeded' | new EscalationReport.DecisionNeeded(UntrustedText.agent('proceed?'), [
            UntrustedText.agent('yes'),
            UntrustedText.agent('no')
        ])
        'CannotVerify' | new EscalationReport.CannotVerify(new CheckRef(0, UntrustedText.manifest('tests')), UntrustedText.subprocess('timeout'), UntrustedText.subprocess('raw output'))
        'CannotExecute' | new EscalationReport.CannotExecute(UntrustedText.subprocess('executor crashed'), [])
        'PipelineMismatch' | new EscalationReport.PipelineMismatch(UntrustedText.branchDocument('stale-stage'))
    }
}
