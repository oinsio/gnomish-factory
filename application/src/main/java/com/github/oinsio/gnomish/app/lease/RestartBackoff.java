package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.DoNotMutate;
import java.time.Duration;
import java.util.Random;
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
 * <p>Public (rather than package-private) so {@code app.serve}'s remote outage gate (task 7.3 of
 * add-base-ref-resolution, FR14) can reuse the same doubling-and-cap policy for its probe interval
 * instead of forking it (design D9: "extend RestartBackoff, do not fork it"), via {@link
 * #nextJitteredBackoff} with its own, independently configured cap ({@link
 * #RestartBackoff(Duration)}) — {@link StandingReaper} keeps using the no-arg constructor and the
 * unjittered {@link #nextBackoff}, so its behavior is unchanged by this reuse.
 *
 * <p>Implements FR4 of fix-reaper-idle-liveness (design D5). Implements FR14 of
 * add-base-ref-resolution (task 7.3).
 */
public final class RestartBackoff {

    /** The exponential-backoff ceiling (design D5): restarts never wait longer than this. */
    public static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    private final Duration cap;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger restartCount = new AtomicInteger();

    /** {@link StandingReaper}'s policy: the {@link #MAX_BACKOFF} ceiling (design D5). */
    public RestartBackoff() {
        this(MAX_BACKOFF);
    }

    /**
     * The same doubling policy with an independently configured ceiling — the remote outage gate's
     * opt-in (FR14 of add-base-ref-resolution): a configured probe-interval cap, not the reaper's
     * fixed {@link #MAX_BACKOFF}.
     *
     * @param cap the backoff ceiling this instance never exceeds; never null
     */
    public RestartBackoff(Duration cap) {
        this.cap = cap;
    }

    /**
     * Computes the backoff to wait before the next respawn and advances the consecutive-failure
     * count: {@code baseInterval} on the first failure since the last clean tick, doubling on
     * each further consecutive failure, capped at this instance's ceiling.
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
                return cap;
            }
        }
        return backoff;
    }

    /**
     * As {@link #nextBackoff}, plus a uniform {@code [0, jitterMaxFraction]} jitter on top of the
     * doubled-and-capped result (the same shape {@code IdleTiming#jittered} applies to the feed's
     * own idle interval) — the remote outage gate's probe schedule (FR14 of
     * add-base-ref-resolution): a jittered interval that grows from the idle interval to this
     * instance's configured cap.
     *
     * @param baseInterval the first backoff, i.e. the idle interval; never null
     * @param random the jitter source; never null
     * @param jitterMaxFraction the jitter ceiling as a fraction of the backoff, e.g. {@code 0.20}
     *     for up to +20%; not negative
     * @return the jittered backoff duration; never null
     */
    public Duration nextJitteredBackoff(Duration baseInterval, Random random, double jitterMaxFraction) {
        Duration backoff = nextBackoff(baseInterval);
        double jitterFraction = random.nextDouble() * jitterMaxFraction;
        long jitterNanos = (long) (backoff.toNanos() * jitterFraction);
        return backoff.plusNanos(jitterNanos);
    }

    // PIT M4 documented exception (build.gradle has the full rationale style, mirrors
    // BackoffPolicy.capped): @DoNotMutate — `>=` vs `>` (ConditionalsBoundaryMutator) is a genuine
    // equivalent mutant here. The two branches only disagree when backoff exactly equals the cap,
    // and at that input `>=` returns the cap now while `>` merely defers one more doubling;
    // doubling is monotonic, so a later iteration always strictly exceeds the cap and returns the
    // same cap value (or the loop ends returning backoff, which also equals the cap) — no reachable
    // baseInterval/failure-count input makes the two branches return a different Duration.
    // RestartBackoffSpec's cap scenario proves the returned value is correct; it cannot additionally
    // distinguish which comparison produced it. The annotation is surgical (this helper only) so the
    // doubling and loop-bound mutants in nextBackoff stay under the gate.
    @DoNotMutate
    private boolean reachedCap(Duration backoff) {
        return backoff.compareTo(cap) >= 0;
    }

    /** Increments and returns the lifetime restart count (never resets), for the ERROR log. */
    int nextRestartCount() {
        return restartCount.incrementAndGet();
    }

    /** The lifetime restart count so far, without incrementing (task 2.5's vitals reader). */
    int restartCount() {
        return restartCount.get();
    }

    /**
     * Resets the consecutive-failure count: a respawned worker completed one full clean tick, or —
     * the remote outage gate's reuse (FR14) — the first successful base refresh after a close.
     */
    public void markCleanTick() {
        consecutiveFailures.set(0);
    }
}
