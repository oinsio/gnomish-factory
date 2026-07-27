package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.domain.engine.TaskState
import spock.lang.Specification

/**
 * TakeResult: the runner-level result of one take run (design D2) — Delivered,
 * AwaitingHuman, Aborted, Revoked, Skipped. Covers component round-trip and the
 * blank-rejection guard shared by every free-text field.
 *
 * Implements FR18, D2, D3 of add-tracker-port.
 */
class TakeResultSpec extends Specification {

    private static final TaskState STATE = TaskState.atStageStart('implement')

    // D2, D3: Delivered exposes its final state and summary exactly as constructed
    def "Delivered exposes finalState and summary exactly as constructed"() {
        when:
        def result = new TakeResult.Delivered(STATE, 'done')

        then:
        result.finalState() == STATE
        result.summary() == 'done'
    }

    // D3: AwaitingHuman exposes its final state, park reason and report exactly as constructed
    def "AwaitingHuman exposes finalState, reason and report exactly as constructed"() {
        when:
        def result = new TakeResult.AwaitingHuman(STATE, ParkReason.INFRA, 'fix and retry')

        then:
        result.finalState() == STATE
        result.reason() == ParkReason.INFRA
        result.report() == 'fix and retry'
    }

    // D16: EmptyQueue carries no fields at all — nothing was claimed, nothing to summarize
    def "EmptyQueue is a plain marker with no fields"() {
        when:
        def result = new TakeResult.EmptyQueue()

        then:
        result instanceof TakeResult
    }

    // D2: Skipped carries no TaskState — there is none, since no engine run happened
    def "Skipped exposes only its reason"() {
        when:
        def result = new TakeResult.Skipped('lost claim race')

        then:
        result.reason() == 'lost claim race'
    }

    // FR18: every free-text field must describe what happened, since a caller renders
    //     a report from it alone
    def "blank #component is rejected with the component name in the message"() {
        when:
        factory()

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("TakeResult.$component")

        where:
        component | factory
        'summary' | { -> new TakeResult.Delivered(STATE, '   ') }
        'report'  | { -> new TakeResult.AwaitingHuman(STATE, ParkReason.CHECKPOINT, '') }
        'cause'   | { -> new TakeResult.Aborted(STATE, '') }
        'note'    | { -> new TakeResult.Revoked(STATE, '') }
        'reason'  | { -> new TakeResult.Skipped('') }
    }
}
