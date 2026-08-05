package com.github.oinsio.gnomish.app.serve;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Maintains the daemon's current {@link DaemonLifecycleView} (FR4 of add-serve-observability) —
 * mirrors {@link FeedViewTracker}'s shape exactly: one {@code volatile} reference, published as a
 * whole immutable view on every update so a concurrent reader (the snapshot writer thread) never
 * sees a torn combination of fields. Starts life in {@link DaemonLifecycleState#RUNNING} — the
 * daemon has no earlier lifecycle state to report.
 *
 * <p>Implements FR4 of add-serve-observability. Implements FR1 of add-serve-observability (design
 * D4): {@link #transitionTo} and {@link #stop} wake the injected {@link DirtyNotifier} whenever
 * the tracked state actually changes — the same vantage point that resets {@code since} — so a
 * same-state re-entry never fires a spurious immediate write. Task 3.2 named this holder as the
 * missing piece that deferred wiring lifecycle transitions into the dirty-flag trigger set; this
 * class is that piece.
 */
public final class LifecycleStateTracker {

    private volatile DaemonLifecycleView view;
    private final DirtyNotifier dirtyNotifier;

    /**
     * Equivalent to {@link #LifecycleStateTracker(Instant, DirtyNotifier)} with a no-op notifier —
     * for callers/tests with no writer to wake.
     *
     * @param constructedAt the instant used as the initial {@code since}; never null
     */
    public LifecycleStateTracker(Instant constructedAt) {
        this(constructedAt, DirtyNotifier.NOOP);
    }

    /**
     * @param constructedAt the instant used as the initial {@code since}; never null
     * @param dirtyNotifier woken by {@link #transitionTo}/{@link #stop} on an actual state change
     *     (FR1 of add-serve-observability, design D4); {@link DirtyNotifier#NOOP} absent a writer
     *     to wake
     */
    public LifecycleStateTracker(Instant constructedAt, DirtyNotifier dirtyNotifier) {
        this.view = new DaemonLifecycleView(DaemonLifecycleState.RUNNING, constructedAt, null);
        this.dirtyNotifier = dirtyNotifier;
    }

    /** The most recently observed {@link DaemonLifecycleView}; never null. */
    public DaemonLifecycleView view() {
        return view;
    }

    /**
     * Moves the tracked state to {@code newState} at instant {@code at}, resetting {@code since}
     * only when {@code newState} differs from the currently reported state — mirroring {@link
     * FeedViewTracker#transitionTo}'s own "only on an actual change" rule.
     *
     * @param newState the non-terminal state to move to; must not be {@link
     *     DaemonLifecycleState#STOPPED} — use {@link #stop} for the terminal transition, which
     *     requires a reason
     * @throws IllegalArgumentException if {@code newState} is {@link DaemonLifecycleState#STOPPED}
     */
    public void transitionTo(DaemonLifecycleState newState, Instant at) {
        if (newState == DaemonLifecycleState.STOPPED) {
            throw new IllegalArgumentException("use stop(reason, at) to transition to STOPPED");
        }
        apply(newState, at, null);
    }

    /**
     * The terminal transition (FR4): moves to {@link DaemonLifecycleState#STOPPED} at instant
     * {@code at}, carrying {@code reason} — written into the final snapshot on graceful exit,
     * which is retained (never deleted).
     *
     * @param reason why the daemon stopped (e.g. {@code "sigterm"}, {@code "drainComplete"}); must
     *     not be blank
     * @throws IllegalArgumentException if {@code reason} is blank
     */
    public void stop(String reason, Instant at) {
        if (reason.isBlank()) {
            throw new IllegalArgumentException("stop reason must not be blank");
        }
        apply(DaemonLifecycleState.STOPPED, at, reason);
    }

    /**
     * {@link DaemonLifecycleState#STOPPED} is terminal (FR4): once reached, no later transition can
     * move the tracker off it, so the final snapshot always shows a terminal state — never a
     * lingering {@code draining}/{@code stopping} left behind by a call that raced past the stop.
     * This is the exact SIGTERM-during-{@code --drain} case: the shutdown hook finalizes to {@code
     * stopped} on its own thread while the main {@code runDrain} body is still walking {@code
     * draining -> stopping}; without the terminal guard the main body's later {@code
     * beginStopping} would overwrite the terminal state. {@code synchronized} serializes those two
     * threads' read-modify-writes so the guard actually holds under that concurrency (reads via
     * {@link #view()} stay lock-free through the {@code volatile} reference).
     */
    private synchronized void apply(DaemonLifecycleState newState, Instant at, @Nullable String reason) {
        DaemonLifecycleView current = view;
        if (current.state() == DaemonLifecycleState.STOPPED || newState == current.state()) {
            return;
        }
        view = new DaemonLifecycleView(newState, at, reason);
        DirtyNotifier.markDirtySafely(dirtyNotifier, "LifecycleStateTracker.apply");
    }
}
