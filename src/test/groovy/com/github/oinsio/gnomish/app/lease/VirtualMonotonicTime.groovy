package com.github.oinsio.gnomish.app.lease

import java.time.Duration

/**
 * A controllable {@link MonotonicTime} for deterministic lease-policy tests: holds a
 * mutable nanosecond counter that {@link #nanoTime} returns and {@link #advance} moves
 * forward, so a spec can drive TTL windows exactly. Starts at a deliberately non-zero,
 * arbitrary origin — the policy must depend only on differences of readings, never on
 * the absolute value.
 *
 * <p>Test fake for the add-claim-heartbeat lease seam; not production code, never
 * PIT-mutated.
 */
class VirtualMonotonicTime implements MonotonicTime {

    /** The current monotonic reading; starts at an arbitrary non-zero origin. */
    long nanos = 1_000_000_000_000L

    /** Moves the monotonic counter forward by {@code duration}. */
    void advance(Duration duration) {
        nanos += duration.toNanos()
    }

    @Override
    long nanoTime() {
        nanos
    }
}
