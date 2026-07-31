package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;

/**
 * The seam through which the heartbeat surfaces a lost claim — a {@code heartbeat} that
 * answered {@link com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult.ClaimGone}
 * (the marker was reaped or taken over) — WITHOUT deciding the reaction here. The
 * heartbeat only notifies and drops the dead claim from its held set; the round-boundary
 * reaction (stop at the next boundary, salvage-push best-effort, write no tracker state
 * for the task that is no longer ours, free the slot) is the take run's. One lost claim
 * never stops the whole thread — every other held claim is beaten unaffected on the same tick.
 *
 * <p>{@link ClaimLossFlag} is the real implementation (task 4.4): a thread-safe flag the beat
 * thread sets here and the take run polls at each round boundary to drive that reaction —
 * identical to a revocation — via {@code RevocationHandler} (task 6.1/6.3). {@link #IGNORE}
 * is the no-op sink for wiring and tests that do not exercise the claim-loss path.
 *
 * <p>Implements FR1, FR8 of add-claim-heartbeat.
 */
@FunctionalInterface
public interface ClaimLostSink {

    /** A sink that discards the signal — for wiring and tests off the claim-loss path. */
    ClaimLostSink IGNORE = _ -> {};

    /**
     * Notifies that {@code ref}'s claim marker is gone, so the run can react at its next
     * round boundary. The heartbeat has already stopped beating this claim by the time
     * this is called; the reaction itself belongs to the take run (see {@link ClaimLossFlag}).
     *
     * <p>Implements FR1, FR8 of add-claim-heartbeat.
     *
     * @param ref the task whose claim was lost; never null
     */
    void claimLost(TaskRef ref);
}
