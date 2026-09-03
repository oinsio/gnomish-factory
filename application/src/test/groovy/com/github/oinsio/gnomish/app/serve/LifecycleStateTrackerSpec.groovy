package com.github.oinsio.gnomish.app.serve

import java.time.Instant
import spock.lang.Specification

/**
 * {@link LifecycleStateTracker} (FR4 of add-serve-observability): current state + since,
 * mirroring {@link FeedViewTracker}'s shape, with a {@link DirtyNotifier} fired on actual
 * transitions (FR1, design D4).
 *
 * Implements FR1, FR4 of add-serve-observability.
 */
class LifecycleStateTrackerSpec extends Specification {

    private static final Instant T0 = Instant.parse('2026-08-03T10:00:00Z')

    def "starts in RUNNING as of the constructed instant"() {
        given:
        def tracker = new LifecycleStateTracker(T0)

        expect:
        tracker.view() == new DaemonLifecycleView(DaemonLifecycleState.RUNNING, T0, null)
    }

    def "transitioning to a different state wakes the dirty notifier and resets since"() {
        given:
        def notifier = Mock(DirtyNotifier)
        def tracker = new LifecycleStateTracker(T0, notifier)

        when:
        tracker.transitionTo(DaemonLifecycleState.DRAINING, T0.plusSeconds(5))

        then:
        1 * notifier.markDirty()
        tracker.view() == new DaemonLifecycleView(DaemonLifecycleState.DRAINING, T0.plusSeconds(5), null)
    }

    def "transitioning to the same state does not wake the dirty notifier nor reset since"() {
        given:
        def notifier = Mock(DirtyNotifier)
        def tracker = new LifecycleStateTracker(T0, notifier)

        when:
        tracker.transitionTo(DaemonLifecycleState.RUNNING, T0.plusSeconds(5))

        then:
        0 * notifier.markDirty()
        tracker.view() == new DaemonLifecycleView(DaemonLifecycleState.RUNNING, T0, null)
    }

    def "the one-arg constructor defaults to a no-op notifier"() {
        given:
        def tracker = new LifecycleStateTracker(T0)

        when:
        tracker.transitionTo(DaemonLifecycleState.DRAINING, T0.plusSeconds(1))

        then:
        noExceptionThrown()
    }

    def "transitionTo rejects STOPPED"() {
        given:
        def tracker = new LifecycleStateTracker(T0)

        when:
        tracker.transitionTo(DaemonLifecycleState.STOPPED, T0.plusSeconds(1))

        then:
        thrown(IllegalArgumentException)
    }

    def "stop transitions to STOPPED, wakes the dirty notifier, and carries the reason"() {
        given:
        def notifier = Mock(DirtyNotifier)
        def tracker = new LifecycleStateTracker(T0, notifier)

        when:
        tracker.stop('signal', T0.plusSeconds(9))

        then:
        1 * notifier.markDirty()
        tracker.view() == new DaemonLifecycleView(DaemonLifecycleState.STOPPED, T0.plusSeconds(9), 'signal')
    }

    def "stop rejects a blank reason"() {
        given:
        def tracker = new LifecycleStateTracker(T0)

        when:
        tracker.stop('  ', T0.plusSeconds(1))

        then:
        thrown(IllegalArgumentException)
    }

    // NFR-R1 (task 3.6): a throwing DirtyNotifier must not break the lifecycle tracker's own
    // state transition — transitionTo/stop must still commit the new view.
    def "a throwing dirty notifier does not propagate out of transitionTo or stop"() {
        given:
        DirtyNotifier notifier = {
            -> throw new RuntimeException('notifier boom')
        }
        def tracker = new LifecycleStateTracker(T0, notifier)

        when:
        tracker.transitionTo(DaemonLifecycleState.DRAINING, T0.plusSeconds(5))

        then:
        noExceptionThrown()
        tracker.view() == new DaemonLifecycleView(DaemonLifecycleState.DRAINING, T0.plusSeconds(5), null)

        when:
        tracker.stop('signal', T0.plusSeconds(9))

        then:
        noExceptionThrown()
        tracker.view() == new DaemonLifecycleView(DaemonLifecycleState.STOPPED, T0.plusSeconds(9), 'signal')
    }

    def "once STOPPED, calling stop again with the same terminal state does not wake the notifier"() {
        given:
        def notifier = Mock(DirtyNotifier)
        def tracker = new LifecycleStateTracker(T0, notifier)
        tracker.stop('signal', T0.plusSeconds(9))

        when:
        tracker.stop('drainComplete', T0.plusSeconds(20))

        then:
        0 * notifier.markDirty()
        tracker.view() == new DaemonLifecycleView(DaemonLifecycleState.STOPPED, T0.plusSeconds(9), 'signal')
    }

    // FR4: STOPPED is terminal — a transition to a NON-terminal state after stop must be ignored,
    // so the final snapshot can never be dragged back to draining/stopping. This is the
    // SIGTERM-during-drain race: the hook stops while the main runDrain body is still walking
    // draining -> stopping. Unlike the same-state re-entry above, this exercises the terminal guard
    // proper (a different newState), which the plain same-state short-circuit would let through.
    def "once STOPPED, a later transition to a non-terminal state is ignored"() {
        given:
        def notifier = Mock(DirtyNotifier)
        def tracker = new LifecycleStateTracker(T0, notifier)
        tracker.stop('signal', T0.plusSeconds(9))

        when:
        tracker.transitionTo(DaemonLifecycleState.STOPPING, T0.plusSeconds(20))

        then:
        0 * notifier.markDirty()
        tracker.view() == new DaemonLifecycleView(DaemonLifecycleState.STOPPED, T0.plusSeconds(9), 'signal')
    }
}
