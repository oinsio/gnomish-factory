package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import org.jspecify.annotations.Nullable

/**
 * A rendezvous {@link Sleeper} for deterministic beat-thread lifecycle specs: each
 * {@link #sleep} hands the requested duration to the test through {@link #awaitEntered} and
 * then blocks until the test calls {@link #releaseOne}, so a spec drives the beat loop one
 * tick at a time with no real sleeping. Left un-driven it parks the worker inside the first
 * sleep — exactly what the direct-tick specs want, so those specs read no timing at all.
 *
 * <p>Each {@link #sleep} carries its <em>own</em> release latch, offered to the test through the
 * {@code entered} channel, and {@link #releaseOne} wakes exactly the sleep last observed by
 * {@link #awaitEntered} — never some other parked sleeper. This matters when more than one
 * thread is parked in a sleep at once: {@code StandingReaperSupervisionSpec} fakes a worker's
 * death by invoking its uncaught-exception handler without terminating the thread, so the
 * "dead" worker stays blocked in its interval sleep while the death handler blocks in its
 * backoff sleep. A single shared release channel would let {@code releaseOne} rendezvous with
 * an arbitrary one of those parked sleepers — a race that intermittently woke a stale worker
 * instead of the backoff wait, spawning no fresh worker and failing the respawn assertion.
 *
 * <p>Test fake for the add-claim-heartbeat beat thread; not production code, never
 * PIT-mutated.
 */
class BlockingSleeper implements Sleeper {

    private final SynchronousQueue<Sleep> entered = new SynchronousQueue<>()
    private @Nullable Sleep current

    // One sleep() call: the duration it asked for plus the latch that releases just this call.
    private static final class Sleep {
        final Duration duration
        final CountDownLatch release = new CountDownLatch(1)

        Sleep(Duration duration) {
            this.duration = duration
        }
    }

    @Override
    void sleep(Duration duration) {
        def sleep = new Sleep(duration)
        try {
            entered.put(sleep)
            sleep.release.await()
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new IllegalStateException('sleeper interrupted', e)
        }
    }

    /** Blocks until a worker is inside a sleep, returning the duration it asked for. */
    Duration awaitEntered() {
        current = entered.take()
        current.duration
    }

    /**
     * Waits up to {@code timeoutMillis} for a worker to enter a sleep, returning the duration it
     * asked for, or {@code null} if it did not sleep again within the window — the signal a spec
     * uses to prove the beat thread stopped (it found no held claim and returned rather than
     * looping back into another sleep).
     */
    @Nullable
    Duration awaitEntered(long timeoutMillis) {
        def sleep = entered.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        if (sleep == null) {
            return null
        }
        current = sleep
        sleep.duration
    }

    /** Lets the sleep last returned by {@link #awaitEntered} return, so that worker runs one more tick. */
    void releaseOne() {
        current.release.countDown()
    }
}
