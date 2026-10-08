package com.github.oinsio.gnomish.app.daemon

import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A {@link Sleeper} on real threads for the concurrency specs of the supervised loop: a sleep
 * announces itself on {@link #entered}, then blocks until the spec opens {@link #release}. It
 * either honours an interrupt (returns at once with the flag restored, as the production sleeper
 * does) or ignores it and keeps blocking (a backoff a stop cannot cut short), recording the
 * interrupt in both cases. Every block is bounded by {@link #BOUND}, so a broken loop fails its
 * feature instead of hanging the build.
 */
final class LatchedSleeper implements Sleeper {

    static final Duration BOUND = Duration.ofSeconds(5)

    final CountDownLatch entered = new CountDownLatch(1)
    final CountDownLatch release = new CountDownLatch(1)
    final CountDownLatch returned = new CountDownLatch(1)
    private final boolean honoursInterrupt
    volatile boolean interrupted

    LatchedSleeper(boolean honoursInterrupt) {
        this.honoursInterrupt = honoursInterrupt
    }

    @Override
    void sleep(Duration duration) {
        entered.countDown()
        long deadline = System.nanoTime() + BOUND.toNanos()
        try {
            blockUntilReleased(deadline)
        } finally {
            returned.countDown()
        }
    }

    private void blockUntilReleased(long deadline) {
        while (true) {
            try {
                release.await(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                return
            } catch (InterruptedException ignored) {
                interrupted = true
                if (honoursInterrupt) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    boolean awaitEntered() {
        entered.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
    }

    boolean returnedWithin(Duration bound) {
        returned.await(bound.toMillis(), TimeUnit.MILLISECONDS)
    }
}
