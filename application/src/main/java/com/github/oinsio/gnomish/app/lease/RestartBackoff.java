package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.DoNotMutate;
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
            if (reachedCap(backoff)) {
                return MAX_BACKOFF;
            }
        }
        return backoff;
    }

    // PIT M4 documented exception (build.gradle has the full rationale style, mirrors
    // BackoffPolicy.capped): @DoNotMutate — `>=` vs `>` (ConditionalsBoundaryMutator) is a genuine
    // equivalent mutant here. The two branches only disagree when backoff exactly equals
    // MAX_BACKOFF, and at that input `>=` returns MAX_BACKOFF now while `>` merely defers one more
    // doubling; doubling is monotonic, so a later iteration always strictly exceeds MAX_BACKOFF and
    // returns the same MAX_BACKOFF constant (or the loop ends returning backoff, which also equals
    // MAX_BACKOFF) — no reachable baseInterval/failure-count input makes the two branches return a
    // different Duration. RestartBackoffSpec's cap scenario proves the returned value is correct; it
    // cannot additionally distinguish which comparison produced it. The annotation is surgical (this
    // helper only) so the doubling and loop-bound mutants in nextBackoff stay under the gate.
    @DoNotMutate
    private static boolean reachedCap(Duration backoff) {
        return backoff.compareTo(MAX_BACKOFF) >= 0;
    }

    /** Increments and returns the lifetime restart count (never resets), for the ERROR log. */
    int nextRestartCount() {
        return restartCount.incrementAndGet();
    }

    /** The lifetime restart count so far, without incrementing (task 2.5's vitals reader). */
    int restartCount() {
        return restartCount.get();
    }

    /** Resets the consecutive-failure count: a respawned worker completed one full clean tick. */
    void markCleanTick() {
        consecutiveFailures.set(0);
    }
}
