package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.port.Clock
import java.time.Duration
import java.time.Instant

/**
 * A controllable {@link Clock} for deterministic engine tests: holds a mutable
 * {@link Instant} (starting at {@link Instant#EPOCH}) that {@link #now} returns, and
 * that {@link #advance} moves forward. Paired with {@link VirtualSleeper}, it makes
 * the external poll loop's timing deterministic and instant.
 *
 * <p>Test fake for the add-stage-engine ports; not production code, never
 * PIT-mutated.
 */
class VirtualClock implements Clock {

    /** The current virtual instant; starts at the epoch. */
    Instant instant = Instant.EPOCH

    VirtualClock() {}

    VirtualClock(Instant start) {
        this.instant = start
    }

    /** Moves virtual time forward by {@code duration}. */
    void advance(Duration duration) {
        instant = instant.plus(duration)
    }

    @Override
    Instant now() {
        instant
    }
}
