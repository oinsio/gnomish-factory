package com.github.oinsio.gnomish.domain.engine.port;

import java.time.Duration;

/**
 * The injected sleep seam (design D8), the waiting half of {@link
 * com.github.oinsio.gnomish.domain.engine.time.TimeEquipment}: the engine's {@code PendingWait}
 * sleeps on it between the polls of a pending check, and the daemon loops, retries and probes
 * outside the engine wait on it too. Injecting it lets a test supply a virtual sleeper that
 * advances a virtual {@link java.time.InstantSource} instead of blocking, so every such wait runs
 * deterministically and instantly under test (NFR-R3).
 *
 * <p>The contract carries <em>no</em> checked exception: the production adapter is a
 * plain blocking sleep on a virtual thread and handles interruption internally, so the
 * caller never has to thread {@code InterruptedException} through its control flow.
 *
 * <p>Supports D8, NFR-R3 of add-stage-engine.
 */
public interface Sleeper {

    /**
     * Sleeps for {@code duration}. The production adapter blocks the calling virtual
     * thread and handles interruption internally (no checked exception on the contract);
     * a test adapter advances a virtual {@link java.time.InstantSource} by {@code duration} instead of
     * blocking, keeping the wait deterministic (NFR-R3).
     *
     * <p>Supports D8, NFR-R3 of add-stage-engine.
     *
     * @param duration how long to sleep; never null
     */
    void sleep(Duration duration);
}
