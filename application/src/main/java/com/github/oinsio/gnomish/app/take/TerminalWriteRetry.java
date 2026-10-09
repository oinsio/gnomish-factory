package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import java.time.Duration;
import java.time.Instant;

/**
 * Bounded retry for a terminal tracker write (finish/park) against a tracker outage (FR10, D10,
 * NFR-R3 of add-claim-heartbeat): the terminal outcome is already durable in the task branch, so a
 * write that fails because the tracker is unreachable ({@link TrackerUnavailableException}) is
 * retried with exponential backoff while the instance keeps holding the slot, up to a bounded ~10
 * minutes. The write lands within the bound → {@link Result#CONFIRMED}; the tracker stays down past
 * the bound → {@link Result#DEFERRED}, and reconcile-on-resume completes the write from the branch
 * later. Only an outage retries: any other {@link RuntimeException} (a bug, a rejected request) is
 * not caught and surfaces at once, so a fault never loops for ten minutes.
 *
 * <p>Time is injected as the {@link TimeEquipment} (the same carrier the external-poll loop uses,
 * {@code ExternalPolling}): the virtual equipment, whose sleeper advances its clock, makes the bound
 * and the backoff deterministic and instant under test (no real sleeping), while the composition
 * root hands the one real equipment it builds (design D20 of
 * supervise-daemon-loops-and-embed-dashboard) — no factory here wires real time.
 *
 * <p>The abort path is deliberately NOT wrapped by this class — {@code AbortHandler} stays
 * fire-and-forget best-effort (a dead tracker never blocks an abort); only finish and park retry.
 *
 * <p>Implements FR10, D10, NFR-R3 of add-claim-heartbeat; FR22 of
 * supervise-daemon-loops-and-embed-dashboard.
 *
 * @param time the injected time equipment: the clock the bound is measured against and the sleeper
 *     waited between attempts; never null
 * @param bound the maximum wall time to keep retrying before giving up; never null, positive
 */
public record TerminalWriteRetry(TimeEquipment time, Duration bound) {

    /** Default hold-the-slot bound per design D10: {@code ~10 minutes}. */
    public static final Duration DEFAULT_BOUND = Duration.ofMinutes(10);

    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    /** The verdict of one bounded terminal-write attempt sequence. */
    public enum Result {
        /** The write landed within the bound. */
        CONFIRMED,
        /** The tracker stayed unreachable past the bound; the write is left for reconcile. */
        DEFERRED
    }

    /**
     * Runs {@code write} and, if it fails with a {@link TrackerUnavailableException}, retries it
     * with exponential backoff until it lands ({@link Result#CONFIRMED}) or the bound elapses
     * ({@link Result#DEFERRED}). A non-outage {@link RuntimeException} propagates unchanged.
     *
     * <p>Implements FR10, D10, NFR-R3 of add-claim-heartbeat.
     *
     * @param write the terminal tracker write to attempt; never null
     * @return {@link Result#CONFIRMED} once the write lands, {@link Result#DEFERRED} on give-up
     */
    public Result confirm(Runnable write) {
        Instant deadline = time.clock().instant().plus(bound);
        Duration backoff = INITIAL_BACKOFF;
        while (true) {
            try {
                write.run();
                return Result.CONFIRMED;
            } catch (TrackerUnavailableException outage) {
                if (time.remaining(deadline).isZero()) {
                    return Result.DEFERRED;
                }
                time.sleeper().sleep(backoff);
                backoff = next(backoff);
            }
        }
    }

    // min() rather than a `doubled > MAX ? MAX : doubled` conditional: the doubling sequence from
    // INITIAL_BACKOFF never lands exactly on MAX_BACKOFF (500ms → 1s → 2s → … → 64s, capped), so a
    // `>` boundary would be a genuine equivalent mutant (`>` and `>=` agree whenever compareTo != 0).
    // Clamping with min carries no relational-boundary mutation and stays fully killed by the
    // exact-backoff-sequence spec.
    private static Duration next(Duration backoff) {
        return Duration.ofMillis(Math.min(backoff.toMillis() * 2, MAX_BACKOFF.toMillis()));
    }
}
