package com.github.oinsio.gnomish.app.serve

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.daemon.SupervisedLoop
import com.github.oinsio.gnomish.app.lease.BlockingSleeper
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * {@link WorktreeJanitor#start}/{@link WorktreeJanitor#stop}, task 7.1 of add-factory-serve
 * (design D10, FR14), now a supervised daemon loop (task 4.1 of
 * supervise-daemon-loops-and-embed-dashboard, design D1, D4, D7): one tick fires immediately (the
 * startup scan) and every {@link WorktreeJanitor#TICK_INTERVAL} thereafter, driven
 * deterministically by the rendezvous {@link BlockingSleeper} — no real sleeping, no polling. A
 * failing tick, an {@code Error} included, is reported by the loop with {@code component=janitor}
 * and the janitor runs again on its next cadence; {@code stop()} ends it. The disposal policy
 * itself is {@link WorktreeJanitorSpec}'s concern; this spec only proves the loop around it.
 *
 * <p>Implements FR14 of add-factory-serve (design D10); FR6 of
 * supervise-daemon-loops-and-embed-dashboard.
 */
@Timeout(10)
class WorktreeJanitorLifecycleSpec extends Specification {

    @TempDir
    Path tempDir

    RegisteredClone registeredClone
    def ticks = new AtomicInteger()
    def sleeper = new BlockingSleeper()
    // FR21 of supervise-daemon-loops-and-embed-dashboard: one virtual source; an aged worktree is aged
    //     against it, not against the wall clock.
    def clock = new VirtualClock(Instant.parse('2026-08-01T00:00:00Z'))

    private static final Duration BOUND = Duration.ofSeconds(2)

    // The loop reports its failures on its own logger (design D6), with component=janitor.
    def loopLogs = LogCaptureSupport.attach(SupervisedLoop)
    WorktreeJanitor janitor

    def setup() {
        registeredClone = RegisteredCloneFixture.unregistered(tempDir.resolve('home'), tempDir.resolve('my-project'))
    }

    def cleanup() {
        if (janitor != null) {
            joinedWithinBound()
        }
        loopLogs.detach()
    }

    private WorktreeJanitor janitorOver(Duration ageThreshold, TaskEnvironmentDisposal disposal, Sleeper waits) {
        janitor = new WorktreeJanitor(registeredClone, ageThreshold, disposal, VirtualTimeEquipment.on(clock, waits), {
            -> Set.of()
        })
    }

    // The janitor's joining stop on a helper thread; false if it did not return in time.
    private boolean joinedWithinBound() {
        Thread.ofVirtual().start { janitor.stopAndJoin() }.join(BOUND)
    }

    private Path agedEnvironment(String key) {
        def dir = Files.createDirectories(registeredClone.worktrees().resolve(key))
        Files.setLastModifiedTime(dir, FileTime.from(clock.instant() - Duration.ofDays(30)))
        dir
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

    // FR14, D10: the daemon-startup tick runs before any sleep — a fresh instance cleans up
    //     immediately rather than waiting a full hour for its first scan.
    def "ticks once at startup, before the first sleep"() {
        given:
        def disposal = { String key ->
            ticks.incrementAndGet()
        } as TaskEnvironmentDisposal
        janitorOver(Duration.ofDays(14), disposal, sleeper)

        when: 'the janitor starts'
        janitor.start()
        def slept = sleeper.awaitEntered()

        then: 'it slept the fixed hourly interval, having already ticked once (a no-op tick here, since no worktrees exist yet)'
        slept == WorktreeJanitor.TICK_INTERVAL
    }

    // FR14, D10: the loop must actually invoke tick(), not merely reach the sleep call — proven here
    //     by an observable effect only tick() produces: an aged, unheld environment gets disposed
    //     of by the time the thread reaches its first sleep, with no tick() call ever made
    //     directly by the test.
    def "the startup tick actually runs and disposes an aged unheld environment"() {
        given: 'a real aged, unheld worktree directory on disk, and a disposal seam that records keys'
        agedEnvironment('task-aged')
        List<String> disposedKeys = Collections.synchronizedList(new ArrayList<String>())
        def disposal = { String key ->
            disposedKeys << key
        } as TaskEnvironmentDisposal
        janitorOver(Duration.ofDays(14), disposal, sleeper)

        when: 'the janitor starts and reaches its first sleep'
        janitor.start()
        sleeper.awaitEntered()

        then: 'tick() ran before that sleep and already disposed of the aged environment'
        disposedKeys == ['task-aged']
    }

    // FR14, D10: after the interval elapses, the janitor ticks again — the hourly cadence.
    def "ticks again after the hourly interval elapses"() {
        given:
        def disposal = { String key ->
            ticks.incrementAndGet()
        } as TaskEnvironmentDisposal
        janitorOver(Duration.ofDays(14), disposal, sleeper)
        janitor.start()
        sleeper.awaitEntered()

        when: 'two intervals elapse'
        sleeper.releaseOne()
        def secondSleep = sleeper.awaitEntered()
        sleeper.releaseOne()
        def thirdSleep = sleeper.awaitEntered()

        then: 'the loop kept ticking on the same fixed interval, tick after tick'
        secondSleep == WorktreeJanitor.TICK_INTERVAL
        thirdSleep == WorktreeJanitor.TICK_INTERVAL
    }

    // FR14, D10; FR6, D2 of supervise-daemon-loops-and-embed-dashboard: a tick that throws (the
    //     disposal seam itself fails) does not kill the janitor — the loop reports it as its own
    //     coded WARN with component=janitor, and the next tick, one interval later, tries again.
    def "a failing tick does not kill the janitor thread"() {
        given: 'one aged, unheld environment whose disposal always throws'
        agedEnvironment('boom')
        def disposal = { String key ->
            ticks.incrementAndGet()
            throw new IllegalStateException('disposal boom')
        } as TaskEnvironmentDisposal
        janitorOver(Duration.ofSeconds(0), disposal, sleeper)

        when: 'the janitor starts, ticks once (throwing), and reaches the next sleep regardless'
        janitor.start()
        def firstSleep = sleeper.awaitEntered()

        then:
        firstSleep == WorktreeJanitor.TICK_INTERVAL

        when: 'one more interval elapses'
        sleeper.releaseOne()
        def secondSleep = sleeper.awaitEntered()

        then: 'the thread survived the failing tick and looped back around for another'
        secondSleep == WorktreeJanitor.TICK_INTERVAL
        ticks.get() == 2

        and: 'the loss is reported by the loop, as the janitor'
        def lost = tickFailures()
        !lost.empty
        lost.every {
            it.level == Level.WARN && it.MDCPropertyMap['component'] == 'janitor'
        }
    }

    // FR6 of supervise-daemon-loops-and-embed-dashboard ("The worktree cleaner survives an
    //     Error"): a run that throws an Error — which the janitor's old RuntimeException catch let
    //     kill its thread — is guarded by the loop, and the cleaner runs again on its next cadence.
    def "the worktree cleaner survives an Error and runs again on its next cadence"() {
        given: 'an aged environment whose first disposal throws an Error'
        agedEnvironment('task-aged')
        List<String> disposed = Collections.synchronizedList([])
        def disposal = { String key ->
            if (ticks.incrementAndGet() == 1) {
                throw new Error('disposal error')
            }
            disposed << key
        } as TaskEnvironmentDisposal
        janitorOver(Duration.ofDays(14), disposal, sleeper)

        when: 'the first run throws the Error, and one interval elapses'
        janitor.start()
        sleeper.awaitEntered()
        sleeper.releaseOne()
        def nextSleep = sleeper.awaitEntered()

        then: 'the cleaner ran again on its cadence and disposed of the environment this time'
        nextSleep == WorktreeJanitor.TICK_INTERVAL
        disposed == ['task-aged']

        and: 'the Error was reported as the janitor\'s lost tick'
        tickFailures().any { it.MDCPropertyMap['component'] == 'janitor' }
    }

    // FR4 of supervise-daemon-loops-and-embed-dashboard, design D4: stop() cuts the interval wait
    //     short and the janitor ends — no tick follows, nothing warns.
    def "stop ends the janitor during its interval wait, with no tick after it"() {
        given:
        def waits = new LatchedSleeper()
        janitorOver(Duration.ofDays(14), { String key -> } as TaskEnvironmentDisposal, waits)
        def stopLogs = LogCaptureSupport.attach(SupervisedLoop, Level.DEBUG)

        when: 'the janitor ticks at startup, enters its wait, and is stopped'
        janitor.start()
        assert waits.entered.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
        janitor.stop()

        then: 'the wait was cut short, the thread ended, and the stop was quiet'
        waits.returned.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
        joinedWithinBound()
        (stopLogs.list.toArray() as List).every {
            it == null || !it.level.isGreaterOrEqual(Level.WARN)
        }

        cleanup:
        stopLogs.detach()
    }

    // FR4, D4: the joining stop the lifecycle specs rely on is itself a stop — it cuts the wait
    //     short and returns only once the janitor's thread has left it.
    def "a joining stop during the interval wait returns only after the wait ended"() {
        given:
        def waits = new LatchedSleeper()
        janitorOver(Duration.ofDays(14), { String key -> } as TaskEnvironmentDisposal, waits)

        when:
        janitor.start()
        assert waits.entered.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
        boolean joined = joinedWithinBound()

        then:
        joined
        waits.returned.count == 0
    }
}
