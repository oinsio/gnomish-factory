package com.github.oinsio.gnomish.app.serve

import java.time.Instant
import spock.lang.Specification

/**
 * FeedViewTracker's {@link DirtyNotifier} trigger point (FR1, design D4 of
 * add-serve-observability): {@link FeedViewTracker#transitionTo} wakes the snapshot writer only
 * when the feed state actually changes — the same "only on an actual change" vantage point that
 * resets {@code since} — never on a same-state poll (design D11 spirit: no write storm).
 *
 * Implements FR1 of add-serve-observability.
 */
class FeedViewTrackerDirtyNotifierSpec extends Specification {

    private static final Instant T0 = Instant.parse('2026-08-03T10:00:00Z')

    def "transitioning to a different state wakes the dirty notifier"() {
        given:
        def notifier = Mock(DirtyNotifier)
        def tracker = new FeedViewTracker(FeedState.IDLE_EMPTY, T0, 2, notifier)

        when:
        tracker.transitionTo(FeedState.FILLING, T0.plusSeconds(1))

        then:
        1 * notifier.markDirty()
    }

    def "transitioning to the same state does not wake the dirty notifier"() {
        given:
        def notifier = Mock(DirtyNotifier)
        def tracker = new FeedViewTracker(FeedState.IDLE_EMPTY, T0, 2, notifier)

        when:
        tracker.transitionTo(FeedState.IDLE_EMPTY, T0.plusSeconds(1))

        then:
        0 * notifier.markDirty()
    }

    def "recordPoll never wakes the dirty notifier"() {
        given:
        def notifier = Mock(DirtyNotifier)
        def tracker = new FeedViewTracker(FeedState.IDLE_EMPTY, T0, 2, notifier)

        when:
        tracker.recordPoll(T0.plusSeconds(1), 3)

        then:
        0 * notifier.markDirty()
    }

    // NFR-R1 (task 3.6): a throwing DirtyNotifier must not break the feed's own state
    // transition — transitionTo must still commit the new view.
    def "a throwing dirty notifier does not propagate out of transitionTo"() {
        given:
        DirtyNotifier notifier = { -> throw new RuntimeException('notifier boom') }
        def tracker = new FeedViewTracker(FeedState.IDLE_EMPTY, T0, 2, notifier)

        when:
        tracker.transitionTo(FeedState.FILLING, T0.plusSeconds(1))

        then:
        noExceptionThrown()
        tracker.view().state() == FeedState.FILLING
    }

    def "the three-arg constructor defaults to a no-op notifier"() {
        given:
        def tracker = new FeedViewTracker(FeedState.IDLE_EMPTY, T0, 2)

        when:
        tracker.transitionTo(FeedState.FILLING, T0.plusSeconds(1))

        then:
        noExceptionThrown()
    }
}
