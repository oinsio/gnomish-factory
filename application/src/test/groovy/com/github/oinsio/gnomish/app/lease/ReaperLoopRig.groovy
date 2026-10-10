package com.github.oinsio.gnomish.app.lease

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.app.daemon.SupervisedLoop
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The shared rig of the standing reaper's loop specs (FR6 of
 * supervise-daemon-loops-and-embed-dashboard): a real {@link StandingReaper} on a real thread
 * whose sleeper never blocks, so its interval waits and its restart backoffs both land in one
 * {@link #journal}, in the order the reaper's threads made them, next to its numbered ticks. A
 * tick body ends the run with {@code #stopHere()}; every wait on the rig is bounded, so a broken
 * loop fails its feature rather than hanging it.
 *
 * <p>Lines are read from the supervised loop's own logger, which is where the reaper's loop
 * events are emitted now that the loop is the component's ({@code DAEMON_LOOP_*}, design D6).
 */
final class ReaperLoopRig {

    static final Duration BOUND = Duration.ofSeconds(5)
    static final int RUNAWAY = 200

    /** A failure the loop's guard cannot even describe: reporting it throws, so the thread dies. */
    static final class Unrenderable extends Error {
        @Override
        String getMessage() {
            throw new IllegalStateException('the failure cannot be rendered')
        }
    }

    final List<String> journal = Collections.synchronizedList([])
    final AtomicInteger ticks = new AtomicInteger()
    final AtomicInteger sleeps = new AtomicInteger()
    final CountDownLatch done = new CountDownLatch(1)
    final VirtualClock clock = new VirtualClock()
    final LogCaptureSupport logs = LogCaptureSupport.attach(SupervisedLoop, Level.DEBUG)
    StandingReaper reaper

    /**
     * Builds the reaper. {@code body(n)} runs as tick {@code n}; {@code sleepHook(n, d)} runs as the
     * {@code n}th sleep of {@code d}, after the journal recorded it.
     */
    StandingReaper build(Duration interval, Closure body, Closure sleepHook = { n, d -> }) {
        def sleeper = { Duration d ->
            int n = sleeps.incrementAndGet()
            guardRunaway(n)
            journal << "sleep ${d}".toString()
            sleepHook(n, d)
        } as Sleeper
        def duty = { Collection<TaskRef> own ->
            int n = ticks.incrementAndGet()
            guardRunaway(n)
            journal << "tick${n}".toString()
            body(n)
        } as ReaperDuty
        reaper = new StandingReaper(duty, interval, {
            -> []
        }, VirtualTimeEquipment.on(clock, sleeper))
    }

    private void guardRunaway(int n) {
        if (n> RUNAWAY) {
            stopHere()
            throw new IllegalStateException('reaper loop ran away')
        }
    }

    /** Requests the stop from inside the reaper's thread and releases {@link #runToStop()}. */
    void stopHere() {
        reaper.stop()
        done.countDown()
    }

    /** Starts the reaper, waits for a tick to call {@link #stopHere()}, then joins its threads. */
    void runToStop() {
        reaper.start()
        assert done.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
        assert joinWithinBound()
    }

    /** The reaper's joining stop on a helper thread; false if it did not return in time. */
    boolean joinWithinBound() {
        def joiner = Thread.ofVirtual().start { reaper.stopAndJoin() }
        joiner.join(BOUND)
    }

    List<ILoggingEvent> events(OperatorEvent code) {
        snapshot().findAll { it.formattedMessage.startsWith(code.head()) }
    }

    List<ILoggingEvent> atOrAbove(Level level) {
        snapshot().findAll { it.level.isGreaterOrEqual(level) }
    }

    // A copy that is safe to take while a thread still appends: toArray never throws a CME.
    List<ILoggingEvent> snapshot() {
        (logs.list.toArray() as List<ILoggingEvent>).findAll { it != null }
    }

    void close() {
        if (reaper != null) {
            joinWithinBound()
        }
        logs.detach()
    }
}
