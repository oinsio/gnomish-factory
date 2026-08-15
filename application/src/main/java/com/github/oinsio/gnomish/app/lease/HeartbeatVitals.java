package com.github.oinsio.gnomish.app.lease;

import java.time.Instant;

/**
 * The read-model {@link InstanceHeartbeat} exposes into the snapshot's {@code vitals.heartbeat}
 * entry (task 2.5, add-serve-observability FR7), split from the beat thread's own class so its
 * documentation lives beside the contract rather than swelling the lifecycle file
 * (process-invariants.md). Every method is safe to read from any thread at any time.
 *
 * <p>Implements FR7 of add-serve-observability.
 */
public interface HeartbeatVitals {

    /**
     * This heartbeat's reported worker state: {@code RUNNING} while the worker beats held claims,
     * {@code DIED} once the worker died abnormally and no later {@code register} has started a
     * fresh one, {@code IDLE} otherwise (never started, or stopped normally on an empty held set).
     *
     * @return the current worker state; never null
     */
    HeartbeatWorkerState state();

    /**
     * The last time the beat thread ticked, or the heartbeat's construction instant if it has
     * never ticked.
     *
     * @return the last tick instant; never null
     */
    Instant lastTickAt();

    /**
     * How many claims this heartbeat currently holds, regardless of worker state.
     *
     * @return the held-claim count
     */
    int heldClaims();
}
