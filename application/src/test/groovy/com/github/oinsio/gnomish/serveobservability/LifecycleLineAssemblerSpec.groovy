package com.github.oinsio.gnomish.serveobservability

import java.time.Instant
import spock.lang.Specification

/**
 * {@link LifecycleLineAssembler}: the pure mapping from a {@code started}/{@code
 * stopped(reason)} event to a {@link LifecycleLine} (design D6, FR12).
 *
 * <p>Implements FR12 of add-serve-observability.
 */
class LifecycleLineAssemblerSpec extends Specification {

    private static final InstanceInfo INSTANCE = new InstanceInfo('gnomish-ab12cd', 'worker-1', '0.1.0')

    def "assembles a started line carrying the instance and instant, with no reason"() {
        given:
        def at = Instant.parse('2026-08-03T10:00:00Z')

        when:
        def line = LifecycleLineAssembler.started(INSTANCE, at)

        then:
        line.instance() == INSTANCE
        line.at() == at
        line.event() == new LedgerLifecycleEvent.Started()
    }

    def "assembles a stopped line carrying the reason"() {
        given:
        def at = Instant.parse('2026-08-03T12:00:00Z')

        when:
        def line = LifecycleLineAssembler.stopped(INSTANCE, at, 'signal')

        then:
        line.instance() == INSTANCE
        line.at() == at
        line.event() == new LedgerLifecycleEvent.Stopped('signal')
    }
}
