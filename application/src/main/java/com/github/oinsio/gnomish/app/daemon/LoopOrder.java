package com.github.oinsio.gnomish.app.daemon;

/**
 * Which comes first in each cycle of a {@link SupervisedLoop}: the tick or the wait (design D1 of
 * supervise-daemon-loops-and-embed-dashboard). A loop that must act as soon as it starts (the
 * janitor, the sweep, the snapshot writer, the dashboard) ticks first; a loop whose first run would
 * only repeat work the process just did on its own startup path (the standing reaper) waits first.
 *
 * <p>Implements FR1 of supervise-daemon-loops-and-embed-dashboard.
 */
public enum LoopOrder {

    /** Tick at once, then wait; every later cycle is wait then tick. */
    TICK_THEN_WAIT,

    /** Wait a full interval before the first tick, and before every later one. */
    WAIT_THEN_TICK
}
