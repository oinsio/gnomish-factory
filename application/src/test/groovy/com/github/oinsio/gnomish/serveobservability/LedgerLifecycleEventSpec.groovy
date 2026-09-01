package com.github.oinsio.gnomish.serveobservability

import spock.lang.Specification

/**
 * {@link LedgerLifecycleEvent}: a ledger {@code lifecycle} line's event, {@link
 * LedgerLifecycleEvent.Started} or {@link LedgerLifecycleEvent.Stopped} — the compiler-enforced
 * sealed shape means only {@code Stopped} carries a reason (FR12), and that reason must not be
 * blank.
 *
 * <p>Implements FR12 of add-serve-observability.
 */
class LedgerLifecycleEventSpec extends Specification {

    def "Started carries no data and is value-equal to another Started"() {
        expect:
        new LedgerLifecycleEvent.Started() == new LedgerLifecycleEvent.Started()
    }

    def "Stopped exposes its reason as constructed"() {
        when:
        def event = new LedgerLifecycleEvent.Stopped('signal')

        then:
        event.reason() == 'signal'
    }

    def "Stopped rejects a blank reason"() {
        when:
        new LedgerLifecycleEvent.Stopped(reason)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('LedgerLifecycleEvent.Stopped.reason')

        where:
        reason << ['', '   ']
    }

    def "Stopped is value-equal by content"() {
        expect:
        new LedgerLifecycleEvent.Stopped('signal') == new LedgerLifecycleEvent.Stopped('signal')

        and:
        new LedgerLifecycleEvent.Stopped('signal') != new LedgerLifecycleEvent.Stopped('drainComplete')
    }
}
