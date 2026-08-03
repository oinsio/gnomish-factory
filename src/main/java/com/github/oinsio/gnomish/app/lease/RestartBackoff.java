package com.github.oinsio.gnomish.app.lease;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Supervised-restart bookkeeping for {@link StandingReaper} (design D5): the exponential backoff
 * to wait before a respawn, and the monotonically increasing restart counter carried by the
 * respawn ERROR log line (NFR-O1, UX2).
 *
 * <p>{@code consecutiveFailures} drives the backoff doubling: it starts at zero, so the first
 * respawn after a clean run backs off the base interval, each further CONSECUTIVE death (no
 * clean tick in between) doubles the previous backoff, and {@link #markCleanTick()} — called
 * once a respawned worker completes one full tick without dying — resets it, so the next death
 * again starts from the base interval. {@code restartCount} is a lifetime total that never
 * resets, so the ERROR log always carries a strictly increasing count restarts can be counted by.
 *
 * <p>Implements FR4 of fix-reaper-idle-liveness (design D5).
 */
final class RestartBackoff {

    /** The exponential-backoff ceiling (design D5): restarts never wait longer than this. */
    static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger restartCount = new AtomicInteger();

    /**
     * Computes the backoff to wait before the next respawn and advances the consecutive-failure
     * count: {@code baseInterval} on the first failure since the last clean tick, doubling on
     * each further consecutive failure, capped at {@link #MAX_BACKOFF}.
     *
     * @param baseInterval the beat interval, i.e. the first backoff; never null
     * @return the backoff duration to sleep before respawning; never null
     */
    Duration nextBackoff(Duration baseInterval) {
        int failures = consecutiveFailures.getAndIncrement();
        Duration backoff = baseInterval;
        for (int i = 0; i < failures; i++) {
            backoff = backoff.multipliedBy(2);
            // PIT: >= survives as an equivalent mutant (testing rule's written-justification
            // exception). Weakening to > only delays the return by one more doubling; doubling
            // is monotonic, so a later iteration always strictly exceeds MAX_BACKOFF and returns
            // the same MAX_BACKOFF constant — no reachable input makes the two branches return a
            // different Duration.
            if (backoff.compareTo(MAX_BACKOFF) >= 0) {
                return MAX_BACKOFF;
            }
        }
        return backoff;
    }

    /** Increments and returns the lifetime restart count (never resets), for the ERROR log. */
    int nextRestartCount() {
        return restartCount.incrementAndGet();
    }

    /** Resets the consecutive-failure count: a respawned worker completed one full clean tick. */
    void markCleanTick() {
        consecutiveFailures.set(0);
    }
}
