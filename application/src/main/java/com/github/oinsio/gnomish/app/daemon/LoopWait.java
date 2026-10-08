package com.github.oinsio.gnomish.app.daemon;

import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * How a {@link SupervisedLoop} waits between ticks (design D1 of
 * supervise-daemon-loops-and-embed-dashboard), and how a stop cuts that wait short (design D4).
 * Sealed with exactly two shapes; the steps are package-private, so only the loop drives them.
 *
 * <p>Neither shape swallows an interrupt: both return with the thread's interrupt flag set, and the
 * loop's one interrupt check (design D3) decides whether that was its own stop or a stray.
 *
 * <p>Implements FR1, FR4, FR5 of supervise-daemon-loops-and-embed-dashboard.
 */
public abstract sealed class LoopWait permits LoopWait.FixedInterval, LoopWait.IntervalOrSignal {

    private LoopWait() {}

    /** Blocks the calling worker for one wait; may throw, and may return early with the flag set. */
    abstract void await();

    /** Ends a wait {@code waiter} is in; never blocks (it runs under the loop's lock). */
    abstract void cutShort(Thread waiter);

    /**
     * A fixed interval on an injected {@link Sleeper}, which restores the interrupt flag rather than
     * throwing — the reaper's, the janitor's, the sweep's and the dashboard's wait. A stop cuts it
     * short by interrupting the sleeping worker.
     */
    public static final class FixedInterval extends LoopWait {

        private final Sleeper sleeper;
        private final Duration interval;

        /**
         * @param sleeper the sleep seam (virtual under test); never null
         * @param interval the time between ticks; never null
         */
        public FixedInterval(Sleeper sleeper, Duration interval) {
            this.sleeper = sleeper;
            this.interval = interval;
        }

        @Override
        void await() {
            sleeper.sleep(interval);
        }

        @Override
        void cutShort(Thread waiter) {
            waiter.interrupt();
        }
    }

    /**
     * An interval cut short by a wake {@link #signal()} — the snapshot writer's wait, behind its
     * {@code markDirty()}. Surplus permits are drained after every wake, so any number of signals
     * landing while the worker ticks produce at most one more tick before the full interval applies
     * again. A semaphore rather than a monitor: releasing it never blocks the signalling thread.
     */
    public static final class IntervalOrSignal extends LoopWait {

        private final Duration interval;
        private final Semaphore signals = new Semaphore(0);

        /** @param interval the longest wait absent a signal; never null */
        public IntervalOrSignal(Duration interval) {
            this.interval = interval;
        }

        /** Wakes the loop now, or at its next wait if it is ticking; safe from any thread. */
        public void signal() {
            signals.release();
        }

        @Override
        void await() {
            try {
                // Acquired or timed out, the next step is the same drain: no decision to make.
                //noinspection ResultOfMethodCallIgnored
                signals.tryAcquire(interval.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            signals.drainPermits();
        }

        @Override
        void cutShort(Thread waiter) {
            signal();
        }

        // Package-private: the coalescing spec reads what a wake left behind.
        int pendingSignals() {
            return signals.availablePermits();
        }
    }
}
