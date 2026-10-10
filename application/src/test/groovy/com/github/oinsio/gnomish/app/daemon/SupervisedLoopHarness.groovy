package com.github.oinsio.gnomish.app.daemon

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.status.DaemonComponent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The shared rig of the supervised-loop specs: a loop on virtual time whose numbered ticks run a
 * spec-supplied body, a journal of ticks and waits in the order the worker saw them, and the log
 * capture of the loop's one logger. A body ends the run by calling {@code #stopHere()}, which
 * requests the stop from inside the worker and releases the spec waiting in {@code #runToStop()}.
 *
 * <p>Every wait on it is bounded, so a broken loop fails its feature instead of hanging it (and a
 * mutant is KILLED rather than TIMED_OUT): a loop past {@link #RUNAWAY} ticks or waits is stopped
 * and its worker killed with a plain {@code Error}, and {@code #close()} joins for a bounded time
 * and fails the feature if the loop ran away.
 *
 * <p>Specs kill a worker with a plain {@code Error} (design D5, fixtures): the guard catches
 * {@code Exception} only, so an {@code Error} ends the thread by the rule, not through a crack in
 * the reporting. {@code Wordless} and {@code UnrenderableTwice} serve {@code
 * SupervisedLoopDeathLineSpec} alone, where the death line's own rendering is the subject.
 */
final class SupervisedLoopHarness {

    static final Duration INTERVAL = Duration.ofMinutes(1)
    static final Duration CAP = Duration.ofMinutes(10)
    static final int RUNAWAY = 500
    static final Duration JOIN_BOUND = Duration.ofSeconds(2)

    /** A fault whose own words cannot be read: its message throws, so no log line can carry it. */
    static final class Wordless extends RuntimeException {
        @Override
        String getMessage() {
            throw new IllegalStateException('the message cannot be read')
        }
    }

    /**
     * An {@code Error} that kills the worker and whose own message throws a {@link Wordless} fault:
     * the death line cannot render its cause either.
     */
    static final class UnrenderableTwice extends Error {
        @Override
        String getMessage() {
            throw new Wordless()
        }
    }

    final List<String> journal = Collections.synchronizedList([])
    final AtomicInteger ticks = new AtomicInteger()
    final CountDownLatch done = new CountDownLatch(1)
    /** The loop's clock: its failure streaks roll up and recover on this virtual time only. */
    final VirtualClock clock = new VirtualClock(Instant.parse('2026-10-08T10:00:00Z'))
    final LogCaptureSupport logs
    SupervisedLoop loop
    volatile boolean runaway

    SupervisedLoopHarness(Level level) {
        logs = LogCaptureSupport.attach(SupervisedLoop, level)
    }

    /** A {@link LoopWait.FixedInterval} whose sleeper journals the wait, then runs {@code hook(n)}. */
    LoopWait.FixedInterval fixedWait(Closure hook = {}) {
        def waits = new AtomicInteger()
        new LoopWait.FixedInterval({ Duration d ->
            int n = waits.incrementAndGet()
            haltIfRunaway(n)
            journal << "wait ${d}".toString()
            hook(n)
        } as Sleeper, INTERVAL)
    }

    /**
     * A {@link LoopWait.FixedInterval} of {@code interval} whose sleeper journals the wait and moves
     * {@link #clock} by it — the loop's time passes only as its own waits elapse.
     */
    LoopWait.FixedInterval clockedWait(Duration interval) {
        def waits = new AtomicInteger()
        new LoopWait.FixedInterval({ Duration d ->
            haltIfRunaway(waits.incrementAndGet())
            journal << "wait ${d}".toString()
            clock.advance(d)
        } as Sleeper, interval)
    }

    SupervisedLoop build(LoopOrder order, LoopWait wait, Closure body,
            RestartPolicy policy = new RestartPolicy.Unbounded(INTERVAL),
            Sleeper backoffSleeper = { Duration d ->
                journal << "backoff ${d}".toString()
            } as Sleeper) {
        def shape = new LoopShape(DaemonComponent.JANITOR, order, wait, policy)
        loop = new SupervisedLoop(shape, {
            int n = ticks.incrementAndGet()
            haltIfRunaway(n)
            journal << "tick${n}".toString()
            body(n)
        } as Runnable, VirtualTimeEquipment.on(clock, backoffSleeper))
    }

    private void haltIfRunaway(int n) {
        if (n> RUNAWAY) {
            runaway = true
            stopHere()
            throw new Error('loop ran away')
        }
    }

    void stopHere() {
        loop.stop()
        done.countDown()
    }

    /** Starts the loop, waits for a body to call {@link #stopHere()}, then joins every worker. */
    void runToStop() {
        loop.start()
        assert done.await(JOIN_BOUND.toMillis(), TimeUnit.MILLISECONDS)
        assert stopAndJoinWithinBound()
    }

    /** {@link SupervisedLoop#stopAndJoin()} on a helper thread; false if it did not return in time. */
    boolean stopAndJoinWithinBound() {
        def joiner = Thread.ofVirtual().start { loop.stopAndJoin() }
        joiner.join(JOIN_BOUND)
    }

    /** What the last {@link #awaitEndWithinBound()} answered: true when the loop gave up. */
    volatile Boolean gaveUp

    /** {@link SupervisedLoop#awaitEnd()} on a helper thread; false if it did not return in time. */
    boolean awaitEndWithinBound() {
        def joiner = Thread.ofVirtual().start { gaveUp = loop.awaitEnd() }
        joiner.join(JOIN_BOUND)
    }

    List<ILoggingEvent> events(OperatorEvent code) {
        snapshot().findAll { it.formattedMessage.startsWith(code.head()) }
    }

    List<ILoggingEvent> atLevel(Level level) {
        snapshot().findAll { it.level == level }
    }

    // A copy that is safe to take while a worker still appends: toArray never throws a CME.
    List<ILoggingEvent> snapshot() {
        (logs.list.toArray() as List<ILoggingEvent>).findAll { it != null }
    }

    void close() {
        if (loop != null) {
            stopAndJoinWithinBound()
        }
        logs.detach()
        assert !runaway
    }
}
