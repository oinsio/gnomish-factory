package com.github.oinsio.gnomish.domain.engine.port;

import java.time.Duration;

/**
 * The engine's injected sleep seam (design D8), used by the external poll loop to wait
 * a check's interval between polls. Injecting it lets a test supply a virtual sleeper
 * that advances a virtual {@link Clock} instead of blocking, so the poll loop runs
 * deterministically and instantly under test (NFR-R3).
 *
 * <p>The contract carries <em>no</em> checked exception: the production adapter is a
 * plain blocking sleep on a virtual thread and handles interruption internally, so the
 * engine's poll loop never has to thread {@code InterruptedException} through its
 * control flow.
 *
 * <p>Supports D8, NFR-R3 of add-stage-engine.
 */
public interface Sleeper {

    /**
     * Sleeps for {@code duration}. The production adapter blocks the calling virtual
     * thread and handles interruption internally (no checked exception on the contract);
     * a test adapter advances a virtual {@link Clock} by {@code duration} instead of
     * blocking, keeping the poll loop deterministic (NFR-R3).
     *
     * <p>Supports D8, NFR-R3 of add-stage-engine.
     *
     * @param duration how long to sleep; never null
     */
    void sleep(Duration duration);
}
