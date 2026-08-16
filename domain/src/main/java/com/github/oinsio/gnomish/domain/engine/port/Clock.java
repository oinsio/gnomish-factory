package com.github.oinsio.gnomish.domain.engine.port;

import java.time.Instant;

/**
 * The engine's injected source of the current time (design D8). Every timestamp and
 * every deadline the engine computes — check durations, the external poll loop's
 * timeout — reads {@link #now()} rather than the system clock, so a test can supply a
 * controllable time source and make those computations deterministic (NFR-R3).
 *
 * <p>This is deliberately a one-method engine-owned seam, <em>not</em>
 * {@link java.time.Clock}: the engine needs only "what instant is it now?", and a
 * minimal single-method port pairs cleanly with a virtual test clock (advanced by the
 * companion {@link Sleeper}) without dragging in {@code java.time.Clock}'s zone and
 * tick surface the engine never uses.
 *
 * <p>Supports D8, NFR-R3 of add-stage-engine.
 */
public interface Clock {

    /**
     * Returns the current instant as this time source sees it. The production adapter
     * reads the system clock; a test adapter returns a controllable virtual instant,
     * making poll loops and timestamps deterministic (NFR-R3).
     *
     * <p>Supports D8, NFR-R3 of add-stage-engine.
     *
     * @return the current instant; never null
     */
    Instant now();
}
