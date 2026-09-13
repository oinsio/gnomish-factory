package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.lease.RestartBackoff;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;

/**
 * {@link RemoteOutageGate}'s probe-scheduling policy, extracted so the gate's own file stays an
 * outage/observability state machine rather than also owning "when is the next probe due"
 * (process-invariants.md file-size target). This class answers exactly that question and nothing
 * about outage lifecycle, counters, or logging — it does not know whether the gate is open.
 *
 * <p><b>Mechanics</b> (design D9, FR14 of add-base-ref-resolution): {@link #openedFreshly()} arms
 * the pending interval reset and schedules the first probe at the idle interval — {@link
 * RestartBackoff#nextJitteredBackoff} reused rather than forked, never sleeping, only ever
 * compared against the injected {@link Clock}. {@link #probeFailed()} re-arms the next, longer
 * interval off the SAME {@link RestartBackoff} instance, so a gate that closes and reopens before
 * a refresh ever succeeds resumes doubling from where it left off (the flapping-remote case FR14
 * calls out). {@link #onSuccessfulRefresh()} is the only path that resets the interval, and only
 * the first time it is called after {@link #openedFreshly()} armed the pending reset.
 *
 * <p>Implements FR14, NFR-R3, D9 of add-base-ref-resolution.
 */
final class RemoteOutageProbeSchedule {

    private static final double JITTER_MAX_FRACTION = 0.20;

    private final Clock clock;
    private final Random random;
    private final Duration idleInterval;
    private final RestartBackoff backoff;

    private volatile Instant nextProbeAt = Instant.MIN;
    private volatile boolean resetPending;

    /**
     * @param clock the injected time source the schedule is measured against; never null
     * @param random the jitter source for the probe schedule; never null
     * @param idleInterval the probe interval's floor — the feed's own idle poll interval; never
     *     null, positive
     * @param cap the probe interval's ceiling the backoff never exceeds; never null, positive
     */
    RemoteOutageProbeSchedule(Clock clock, Random random, Duration idleInterval, Duration cap) {
        this.clock = clock;
        this.random = random;
        this.idleInterval = idleInterval;
        this.backoff = new RestartBackoff(cap);
    }

    /**
     * Arms the pending interval reset and schedules the first probe at the idle interval, for a
     * gate that just transitioned closed-to-open.
     */
    void openedFreshly() {
        resetPending = true;
        scheduleNext();
    }

    /** Re-arms the next, longer probe interval off the same backoff instance, after a failure. */
    void probeFailed() {
        scheduleNext();
    }

    /**
     * The only path that resets the probe interval to the idle floor: the first successful base
     * refresh observed after {@link #openedFreshly()} armed the pending reset. A no-op before any
     * open, and a no-op on every call after the first following an open.
     */
    void onSuccessfulRefresh() {
        if (resetPending) {
            backoff.markCleanTick();
            resetPending = false;
        }
    }

    /** Whether the scheduled probe instant has passed; cheap, read every feed cycle. */
    boolean isDue() {
        return !clock.now().isBefore(nextProbeAt);
    }

    /** When the next probe is scheduled, for the snapshot's {@code remote} section while open. */
    Instant nextProbeAt() {
        return nextProbeAt;
    }

    private void scheduleNext() {
        Duration wait = backoff.nextJitteredBackoff(idleInterval, random, JITTER_MAX_FRACTION);
        nextProbeAt = clock.now().plus(wait);
    }
}
