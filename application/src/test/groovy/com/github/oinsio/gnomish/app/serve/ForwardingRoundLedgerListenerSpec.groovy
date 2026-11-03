package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import spock.lang.Specification

/**
 * {@link ForwardingRoundLedgerListener} (NFR-O2, UX3 of define-executor-contract): the stand-in the
 * serve root hands the slots' engines before the {@code roundOutcome} writer exists — a no-op until
 * bound, then a plain forward.
 */
class ForwardingRoundLedgerListenerSpec extends Specification {

    static final EngineEvent EVENT = new EngineEvent.AttemptStarted(new AttemptKey('TASK-1', 'build', 0))

    def "an event before bind is a harmless no-op"() {
        when:
        new ForwardingRoundLedgerListener().onEvent(EVENT)

        then:
        noExceptionThrown()
    }

    def "an event after bind reaches the bound writer"() {
        given:
        def listener = new ForwardingRoundLedgerListener()
        def received = []
        listener.bind({ e -> received << e })

        when:
        listener.onEvent(EVENT)

        then:
        received == [EVENT]
    }
}
