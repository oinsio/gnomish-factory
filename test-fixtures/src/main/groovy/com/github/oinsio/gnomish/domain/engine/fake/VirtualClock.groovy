package com.github.oinsio.gnomish.domain.engine.fake

import java.time.Duration
import java.time.Instant
import java.time.InstantSource

/**
 * A controllable {@link InstantSource} for deterministic tests: holds a mutable
 * {@link Instant} (starting at {@link Instant#EPOCH}) that {@link #instant} returns, and
 * that {@link #advance} moves forward. Paired with {@link VirtualSleeper}, it makes
 * the external poll loop's timing deterministic and instant.
 *
 * <p>The current instant is a private field, not a Groovy property: a property named
 * {@code instant} would shadow the {@link #instant()} method (design D18 of
 * supervise-daemon-loops-and-embed-dashboard). Time moves only through {@link #advance}.
 *
 * <p>Test fake for the add-stage-engine ports; not production code, never
 * PIT-mutated. Implements FR21 of supervise-daemon-loops-and-embed-dashboard.
 */
class VirtualClock implements InstantSource {

    /** The current virtual instant; starts at the epoch. */
    private Instant current = Instant.EPOCH

    VirtualClock() {}

    VirtualClock(Instant start) {
        this.current = start
    }

    /** Moves virtual time forward by {@code duration}. */
    void advance(Duration duration) {
        current = current.plus(duration)
    }

    @Override
    Instant instant() {
        current
    }
}
