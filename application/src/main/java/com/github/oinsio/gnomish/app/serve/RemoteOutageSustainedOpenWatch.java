package com.github.oinsio.gnomish.app.serve;

import java.time.Duration;
import java.time.Instant;

/**
 * The one-shot latch behind {@link RemoteOutageGate}'s sustained-open ERROR (task 7.4 of
 * add-base-ref-resolution): fires at most once per outage, the first time the outage's elapsed
 * duration crosses a threshold. Extracted so the gate's own file does not also own this
 * comparison-and-latch bookkeeping (process-invariants.md file-size target).
 *
 * <p>Implements FR14, NFR-O1 of add-base-ref-resolution.
 */
final class RemoteOutageSustainedOpenWatch {

    private final Duration threshold;
    private volatile boolean fired;

    /** @param threshold how long an outage may run before this watch is due to fire; never null */
    RemoteOutageSustainedOpenWatch(Duration threshold) {
        this.threshold = threshold;
    }

    /** Re-arms the watch for a freshly opened outage. */
    void reset() {
        fired = false;
    }

    /**
     * Whether the watch should fire now: not already fired this outage, and {@code openedAt} to
     * {@code now} has reached the threshold. Firing latches, so a later call for the same outage
     * always answers false.
     */
    boolean shouldFire(Instant openedAt, Instant now) {
        if (fired || Duration.between(openedAt, now).compareTo(threshold) < 0) {
            return false;
        }
        fired = true;
        return true;
    }

    /** The threshold this watch fires at, for the ERROR line's own message. */
    Duration threshold() {
        return threshold;
    }
}
