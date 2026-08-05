package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerHealthTracker
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import spock.lang.Specification

/**
 * {@link TrackerHealthAssembler}: carries {@link TrackerHealthTracker#lastSuccessAt()} and {@link
 * TrackerHealthTracker#consecutiveFailures()} verbatim into the snapshot's {@code tracker}
 * section (FR8, D12).
 */
class TrackerHealthAssemblerSpec extends Specification {

    private Tracker delegate = Mock()
    private VirtualClock clock = new VirtualClock()

    def "before any call, the assembled health has no lastSuccessAt and no failures"() {
        given:
        def tracker = new TrackerHealthTracker(delegate, clock)

        expect:
        TrackerHealthAssembler.assemble(tracker) == new TrackerHealth(null, 0)
    }

    def "carries lastSuccessAt and consecutiveFailures verbatim from the decorator"() {
        given:
        def tracker = new TrackerHealthTracker(delegate, clock)
        clock.advance(Duration.ofSeconds(5))
        delegate.listOpen() >> { throw new RuntimeException('boom') }

        when:
        try {
            tracker.listOpen()
        } catch (RuntimeException ignored) {
        }

        then:
        TrackerHealthAssembler.assemble(tracker) == new TrackerHealth(null, 1)

        when:
        delegate.postNote(_, _) >> null
        tracker.postNote(new com.github.oinsio.gnomish.app.port.tracker.TaskRef('PROJ-1'), 'note')

        then:
        TrackerHealthAssembler.assemble(tracker) == new TrackerHealth(clock.now(), 0)
    }
}
