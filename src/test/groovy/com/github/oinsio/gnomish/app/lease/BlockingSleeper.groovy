package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration
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
 * <p>Test fake for the add-claim-heartbeat beat thread; not production code, never
 * PIT-mutated.
 */
public class BlockingSleeper implements Sleeper {

    private final SynchronousQueue<Duration> entered = new SynchronousQueue<>()
    private final SynchronousQueue<Object> release = new SynchronousQueue<>()

    @Override
    void sleep(Duration duration) {
        try {
            entered.put(duration)
            release.take()
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new IllegalStateException('sleeper interrupted', e)
        }
    }

    /** Blocks until the worker is inside a sleep, returning the duration it asked for. */
    Duration awaitEntered() {
        entered.take()
    }

    /**
     * Waits up to {@code timeoutMillis} for the worker to enter a sleep, returning the duration it
     * asked for, or {@code null} if it did not sleep again within the window — the signal a spec
     * uses to prove the beat thread stopped (it found no held claim and returned rather than
     * looping back into another sleep).
     */
    @Nullable
    Duration awaitEntered(long timeoutMillis) {
        entered.poll(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    /** Lets exactly one sleep return, so the worker runs one more tick. */
    void releaseOne() {
        release.put(Boolean.TRUE)
    }
}
