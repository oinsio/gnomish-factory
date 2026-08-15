package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration

/**
 * A virtual {@link Sleeper} that never blocks: each {@link #sleep} advances the
 * shared {@link VirtualClock} by the slept duration and appends it to the public
 * {@link #slept} list. This lets the engine's poll loop run instantly and
 * deterministically while a spec asserts how long, and how many times, it slept.
 *
 * <p>Test fake for the add-stage-engine ports; not production code, never
 * PIT-mutated.
 */
class VirtualSleeper implements Sleeper {

    private final VirtualClock clock

    /** Every slept duration, in order. */
    final List<Duration> slept = []

    VirtualSleeper(VirtualClock clock) {
        this.clock = clock
    }

    @Override
    void sleep(Duration duration) {
        slept << duration
        clock.advance(duration)
    }
}
