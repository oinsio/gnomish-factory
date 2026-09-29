package com.github.oinsio.gnomish.app.port.tracker

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import spock.lang.Specification

/**
 * {@link TrackerHealthTracker} under a stop (FR12 of fix-operator-blockers): a delegate call that
 * fails on a thread whose interrupt is set failed because the thread was told to stop, not because
 * the tracker is unhealthy. It is rethrown unchanged — the decorator stays transparent (FR8 of
 * add-serve-observability) — but it does not count toward the consecutive-failure streak the
 * snapshot reports, so a daemon stopped mid-call does not leave a tracker-failure reading behind.
 *
 * Implements FR12 of fix-operator-blockers.
 */
class TrackerHealthTrackerInterruptSpec extends Specification {

    private Tracker delegate = Stub()
    private TrackerHealthTracker tracker = new TrackerHealthTracker(delegate, new VirtualClock())

    def cleanup() {
        Thread.interrupted()
    }

    // FR12: the interrupted failure is uncounted and rethrown as it came.
    def "a call that fails on an interrupted thread is rethrown unchanged and not counted"() {
        given:
        def failure = new IllegalStateException('call cancelled')
        delegate.listOpen() >> {
            Thread.currentThread().interrupt()
            throw failure
        }

        when:
        tracker.listOpen()

        then:
        def thrown = thrown(IllegalStateException)
        thrown.is(failure)
        tracker.consecutiveFailures() == 0
        Thread.currentThread().isInterrupted()
    }

    // FR12, control row: a failure on an uninterrupted thread counts.
    def "a call that fails on an uninterrupted thread is counted"() {
        given:
        delegate.listOpen() >> {
            throw new IllegalStateException('tracker unreachable')
        }

        when:
        tracker.listOpen()

        then:
        thrown(IllegalStateException)
        tracker.consecutiveFailures() == 1
    }
}
