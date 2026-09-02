package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.logtext.ShutdownPhase;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one teardown sequence every command exits through (design D6 of
 * harden-logging-observability): close the application context, then stop the logging system so
 * the asynchronous file appender flushes what the run just wrote.
 *
 * <p>The order is the whole point. Spring's own shutdown hook is disabled in {@code
 * FactoryApplication} precisely because it closes the context on a thread of its own, concurrently
 * with a {@code serve} drain that is still producing terminal slot lines; and a logging system
 * stopped before the last line is written loses that line to the async queue. So exactly one owner
 * runs the sequence: {@code serve}'s shutdown hook on a signal-initiated stop, the {@code main}
 * thread on every ordinary return.
 *
 * <p>Which owner it is, is settled by {@link ShutdownPhase}: {@link #onNormalExit()} steps aside
 * entirely once the hook has begun, rather than racing it to the context close. The {@link
 * AtomicBoolean} guard behind {@link #closeAndStopLogging()} then makes a second pass — the hook
 * the JVM fires after a completed drain, a spec driving the sequence twice — a no-op (NFR-R1).
 *
 * <p>A second pass is a no-op, but it is not an immediate <em>return</em>: the caller that loses
 * the guard waits for the sequence to finish. The generic signal hook can lose it to a {@code main}
 * thread that entered {@link #onNormalExit()} a moment before the signal landed, and the JVM halts
 * once its hooks return — without the wait it would halt mid-flush, losing exactly the tail this
 * class exists to keep. The wait is bounded so a context close that hangs cannot turn a stop into a
 * process that never exits.
 *
 * <p>Installed once, statically, for the same reason {@link ShutdownPhase} is static: the two
 * callers are the composition root's {@code main} and a hook body deep inside the serve wiring,
 * with no shared object to hand an instance through. Before installation both entry points are
 * no-ops, which is what lets a spec drive the serve hook without a Spring context at all.
 *
 * <p>Implements FR9, NFR-R1 of harden-logging-observability.
 */
public final class OrderedExit {

    private static final Logger log = LoggerFactory.getLogger(OrderedExit.class);

    /**
     * How long the losing caller waits for the winner's sequence. Long enough for a context close
     * plus an appender flush, short enough that a hung teardown still lets the JVM exit.
     */
    static final long HANDOVER_WAIT_SECONDS = 30;

    private static volatile @Nullable OrderedExit installed;

    private static volatile boolean signalOwned;

    private final Runnable closeContext;
    private final Runnable stopLogging;
    private final AtomicBoolean done = new AtomicBoolean();
    private final CountDownLatch finished = new CountDownLatch(1);

    private OrderedExit(Runnable closeContext, Runnable stopLogging) {
        this.closeContext = closeContext;
        this.stopLogging = stopLogging;
    }

    /**
     * Installs the sequence the composition root owns; a later install replaces the earlier one,
     * so a spec starts from a fresh, not-yet-run guard.
     *
     * @param closeContext closes the application context; never null
     * @param stopLogging stops the logging system, flushing the async appender; never null
     */
    public static void install(Runnable closeContext, Runnable stopLogging) {
        installed = new OrderedExit(closeContext, stopLogging);
        signalOwned = false;
    }

    /**
     * Declares that this run has a shutdown hook of its own driving the sequence — {@code serve},
     * which must drain its slots before the context closes. Called when that hook is
     * <em>registered</em>, not when it fires, so {@link #onSignal()} never has to win a race
     * against it to find out.
     */
    public static void reserveSignalOwner() {
        signalOwned = true;
    }

    /**
     * The composition root's generic signal hook: the sequence a command that registered no hook of
     * its own would otherwise never run, losing the tail of its log file to the async appender.
     * Steps aside when a command owns its own stop.
     */
    public static void onSignal() {
        if (signalOwned) {
            return;
        }
        closeAndStopLogging();
    }

    /**
     * Runs the sequence once: context close, then logging stop. Idempotent — repeated calls, from
     * either owner, change nothing.
     */
    public static void closeAndStopLogging() {
        OrderedExit exit = installed;
        if (exit != null) {
            exit.run();
        }
    }

    /**
     * The ordinary-return path. Defers to the shutdown hook once the stop has begun: the hook is
     * still draining slots, and closing the context out from under it is exactly the race this
     * class exists to remove.
     */
    public static void onNormalExit() {
        if (ShutdownPhase.inProgress()) {
            return;
        }
        closeAndStopLogging();
    }

    private void run() {
        if (!done.compareAndSet(false, true)) {
            awaitFinish();
            return;
        }
        try {
            closeContext.run();
        } catch (RuntimeException contextCloseFailed) {
            // Logging is still up at this point — and this is the last chance to say anything at
            // all, since the very next statement stops it.
            log.warn("application context did not close cleanly; logging is stopped next", contextCloseFailed);
        } finally {
            // In a finally rather than after the catch: an Error thrown out of the close (a bean
            // hitting OutOfMemoryError, a NoClassDefFoundError on a shutdown path) is not a
            // RuntimeException, and skipping the flush on it would discard the queued lines that
            // describe the very crash — while the Error itself still propagates. The release comes
            // last, so a caller waiting on the sequence is freed however it ended.
            stopLogging.run();
            finished.countDown();
        }
    }

    /**
     * Waits for the owner that won the guard to finish the sequence, so a losing caller never
     * reports a teardown that is still running. Bounded, because a close that hangs must not turn
     * a stop into a process that never exits; the expiry is deliberately silent, since the only
     * way to report it is the logging system the winner is in the middle of stopping. An interrupt
     * ends the wait rather than the process — the caller is on its way out either way — and is
     * restored for whoever reads it next.
     */
    @SuppressWarnings("ResultOfMethodCallIgnored") // timed-out vs. counted-down: both let the
    // caller return, and there is no logger left to report the difference to (see javadoc above).
    private void awaitFinish() {
        try {
            finished.await(HANDOVER_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
