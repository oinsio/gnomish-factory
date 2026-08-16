package com.github.oinsio.gnomish.app.serve;

import java.time.Instant;

/**
 * Maintains {@link FeedAutomaton#view()}'s current {@link FeedView} (FR5 of
 * add-serve-observability, design D3) — extracted only to keep {@link FeedAutomaton} within the
 * file-size limit (process-invariants.md), exactly like {@link FeedStateLogger} and {@link
 * FeedCycle} were. Holds one {@code volatile} reference, published as a whole immutable {@link
 * FeedView} on every update so a concurrent reader (the future snapshot writer thread) never sees
 * a torn combination of fields.
 *
 * <p>Implements FR5 of add-serve-observability. Implements FR1 of add-serve-observability (design
 * D4): {@link #transitionTo} wakes the injected {@link DirtyNotifier} whenever the tracked state
 * actually changes — the same vantage point that resets {@code since} — so a same-state poll
 * never fires a spurious immediate write.
 */
final class FeedViewTracker {

    private volatile FeedView view;
    private final DirtyNotifier dirtyNotifier;

    /**
     * Equivalent to {@link #FeedViewTracker(FeedState, Instant, int, DirtyNotifier)} with a no-op
     * notifier — every caller predating the observability writer (task 5.x wires the real one).
     *
     * @param initialState the state to report before any cycle has run (a construction-time idle
     *     baseline); never null
     * @param constructedAt the instant used as both the initial {@code since} and {@code
     *     lastPollAt}; never null
     * @param wipLimit the configured WIP limit, carried into every {@link FeedView}
     */
    FeedViewTracker(FeedState initialState, Instant constructedAt, int wipLimit) {
        this(initialState, constructedAt, wipLimit, DirtyNotifier.NOOP);
    }

    /**
     * @param initialState the state to report before any cycle has run (a construction-time idle
     *     baseline); never null
     * @param constructedAt the instant used as both the initial {@code since} and {@code
     *     lastPollAt}; never null
     * @param wipLimit the configured WIP limit, carried into every {@link FeedView}
     * @param dirtyNotifier woken by {@link #transitionTo} on an actual state change (FR1 of
     *     add-serve-observability, design D4); {@link DirtyNotifier#NOOP} absent a writer to wake
     */
    FeedViewTracker(FeedState initialState, Instant constructedAt, int wipLimit, DirtyNotifier dirtyNotifier) {
        this.view = new FeedView(initialState, constructedAt, constructedAt, 0, wipLimit);
        this.dirtyNotifier = dirtyNotifier;
    }

    /** The most recently observed {@link FeedView}; never null. */
    FeedView view() {
        return view;
    }

    /**
     * Updates {@code lastPollAt}/{@code openFronts} to this poll's values, unconditionally —
     * called once per completed poll, independent of whether the state changed.
     */
    void recordPoll(Instant polledAt, int openFronts) {
        FeedView current = view;
        view = new FeedView(current.state(), current.since(), polledAt, openFronts, current.wipLimit());
    }

    /**
     * Moves the tracked state to {@code newState} at instant {@code at}, resetting {@code since}
     * only when {@code newState} differs from the currently reported state — mirroring {@link
     * FeedStateLogger#onTransition}'s own "only on an actual change" rule.
     */
    void transitionTo(FeedState newState, Instant at) {
        FeedView current = view;
        if (newState == current.state()) {
            return;
        }
        view = new FeedView(newState, at, current.lastPollAt(), current.openFronts(), current.wipLimit());
        DirtyNotifier.markDirtySafely(dirtyNotifier, "FeedViewTracker.transitionTo");
    }
}
