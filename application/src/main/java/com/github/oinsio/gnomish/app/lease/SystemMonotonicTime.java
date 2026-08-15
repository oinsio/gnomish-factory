package com.github.oinsio.gnomish.app.lease;

/**
 * The production {@link MonotonicTime}: reads {@link System#nanoTime()}, the JVM's
 * monotonic, jump-free nanosecond counter (design D2). A one-line delegation, in the
 * same minimal-seam idiom as the engine's {@code SystemClock}/{@code ThreadSleeper}.
 *
 * <p>Supports FR2, D2, NFR-R1 of add-claim-heartbeat.
 */
public final class SystemMonotonicTime implements MonotonicTime {

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}
