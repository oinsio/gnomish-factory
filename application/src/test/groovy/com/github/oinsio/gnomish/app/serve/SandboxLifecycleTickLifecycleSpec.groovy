package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.daemon.SupervisedLoop
import com.github.oinsio.gnomish.app.lease.BlockingSleeper
import com.github.oinsio.gnomish.app.lease.CachedOpenTaskListing
import com.github.oinsio.gnomish.app.lease.LivenessOracle
import com.github.oinsio.gnomish.app.lease.StalenessMemory
import com.github.oinsio.gnomish.app.lease.SystemMonotonicTime
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.Timeout

/**
 * {@link SandboxLifecycleTick#start}/{@link SandboxLifecycleTick#stop}, task 4.1 of
 * add-serve-sandbox-lifecycle (design D7), now a supervised daemon loop (task 4.2 of
 * supervise-daemon-loops-and-embed-dashboard, design D1, D4, D7): one tick fires immediately and
 * every configured interval thereafter, driven deterministically by the rendezvous {@link
 * BlockingSleeper}. A failing tick, an {@code Error} included, is reported by the loop with {@code
 * component=sweep} and the sweep runs again on its next cadence; {@code stop()} ends it.
 *
 * <p>Implements FR6 of add-serve-sandbox-lifecycle; FR4, FR6 of
 * supervise-daemon-loops-and-embed-dashboard.
 */
@Timeout(10)
class SandboxLifecycleTickLifecycleSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final Duration BOUND = Duration.ofSeconds(2)

    def cloneDir = Path.of('/tmp/project')
    def clock = new VirtualClock(Instant.parse('2026-08-07T12:00:00Z'))
    def livenessOracle = new LivenessOracle(new CachedOpenTaskListing(), new StalenessMemory(new SystemMonotonicTime(), Duration.ofMinutes(1)))
    def sleeper = new BlockingSleeper()
    def runs = new AtomicInteger()

    // The loop reports its failures on its own logger (design D6), with component=sweep.
    def loopLogs = LogCaptureSupport.attach(SupervisedLoop)
    SandboxLifecycleTick sweep

    def cleanup() {
        if (sweep != null) {
            joinedWithinBound()
        }
        loopLogs.detach()
    }

    private SandboxLifecycleTick sweepOver(SandboxLifecyclePass pass, Sleeper waits) {
        sweep = new SandboxLifecycleTick(pass, livenessOracle, cloneDir, INTERVAL, VirtualTimeEquipment.on(clock, waits))
    }

    // The sweep's joining stop on a helper thread; false if it did not return in time.
    private boolean joinedWithinBound() {
        Thread.ofVirtual().start { sweep.stopAndJoin() }.join(BOUND)
    }

    private List tickFailures() {
        (loopLogs.list.toArray() as List).findAll {
            it != null && it.formattedMessage.startsWith(OperatorEvent.DAEMON_LOOP_TICK_FAILED.head())
        }
    }

    /** A sleeper that blocks until interrupted, restoring the flag as production does. */
    private static final class LatchedSleeper implements Sleeper {
        final CountDownLatch entered = new CountDownLatch(1)
        final CountDownLatch returned = new CountDownLatch(1)

        @Override
        void sleep(Duration duration) {
            entered.countDown()
            try {
                new CountDownLatch(1).await(BOUND.toMillis() * 5, TimeUnit.MILLISECONDS)
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt()
            } finally {
                returned.countDown()
            }
        }
    }

    // FR6, D7 of add-serve-sandbox-lifecycle: the startup tick runs the pass before the first wait,
    //     which is the configured interval.
    def "the startup tick runs the pass before the first sleep of the configured interval"() {
        given:
        def calls = Collections.synchronizedList([])
        sweepOver({ dir, liveness ->
            calls << dir
            ''
        } as SandboxLifecyclePass, sleeper)

        when:
        sweep.start()
        def slept = sleeper.awaitEntered()

        then:
        slept == INTERVAL
        calls == [cloneDir]
    }

    // FR6, D2 of supervise-daemon-loops-and-embed-dashboard: a tick that throws does not end the
    //     sweep — the loop reports it with component=sweep, and the next tick tries again.
    def "a failing tick does not kill the sweep; the loop reports it as the sweep and retries"() {
        given:
        sweepOver({ dir, liveness ->
            runs.incrementAndGet()
            throw new IllegalStateException('boom')
        } as SandboxLifecyclePass, sleeper)

        when:
        sweep.start()
        def firstSleep = sleeper.awaitEntered()
        sleeper.releaseOne()
        def secondSleep = sleeper.awaitEntered()

        then:
        firstSleep == INTERVAL
        secondSleep == INTERVAL
        runs.get() == 2

        and:
        def lost = tickFailures()
        !lost.empty
        lost.every {
            it.level == Level.WARN && it.MDCPropertyMap['component'] == 'sweep'
        }
    }

    // FR6 of supervise-daemon-loops-and-embed-dashboard: an Error — which the old RuntimeException
    //     catch let kill the thread — is guarded by the loop, and the sweep runs again on its cadence.
    def "the sweep survives an Error and runs again on its next cadence"() {
        given:
        def swept = Collections.synchronizedList([])
        sweepOver({ dir, liveness ->
            if (runs.incrementAndGet() == 1) {
                throw new Error('sweep error')
            }
            swept << dir
            ''
        } as SandboxLifecyclePass, sleeper)

        when:
        sweep.start()
        sleeper.awaitEntered()
        sleeper.releaseOne()
        def nextSleep = sleeper.awaitEntered()

        then:
        nextSleep == INTERVAL
        swept == [cloneDir]

        and:
        tickFailures().any { it.MDCPropertyMap['component'] == 'sweep' }
    }

    // FR4, D4 of supervise-daemon-loops-and-embed-dashboard: stop() cuts the interval wait short
    //     and the sweep ends — no tick follows, nothing warns.
    def "stop ends the sweep during its interval wait, with no tick after it"() {
        given:
        def waits = new LatchedSleeper()
        sweepOver({ dir, liveness ->
            runs.incrementAndGet()
            ''
        } as SandboxLifecyclePass, waits)
        def stopLogs = LogCaptureSupport.attach(SupervisedLoop, Level.DEBUG)

        when:
        sweep.start()
        assert waits.entered.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
        sweep.stop()

        then:
        waits.returned.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
        joinedWithinBound()
        runs.get() == 1
        (stopLogs.list.toArray() as List).every {
            it == null || !it.level.isGreaterOrEqual(Level.WARN)
        }

        cleanup:
        stopLogs.detach()
    }

    // FR4, D4: the joining stop the lifecycle specs rely on is itself a stop — it cuts the wait
    //     short and returns only once the sweep's thread has left it.
    def "a joining stop during the interval wait returns only after the wait ended"() {
        given:
        def waits = new LatchedSleeper()
        sweepOver(SandboxLifecyclePass.NONE, waits)

        when:
        sweep.start()
        assert waits.entered.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
        boolean joined = joinedWithinBound()

        then:
        joined
        waits.returned.count == 0
    }
}
