package com.github.oinsio.gnomish.app.lease

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.Timeout

/**
 * StandingReaper's THREADED loop resilience (design D4; FR3, FR4 of
 * fix-reaper-idle-liveness — "the reaper thread survives abnormal faults"). Unlike
 * {@code StandingReaperSpec}, which drives the synchronous {@code tick()} seam directly, these
 * specs drive the real worker thread through {@code loop()} with a {@link BlockingSleeper}
 * rendezvous: {@code loop()} is assumed to sleep the interval, THEN run one reap tick, both
 * wrapped in a single {@code catch (Throwable)} (mirroring {@code InstanceHeartbeat.loop()}'s
 * sleep-then-tick pacing, but widened per D4 from {@code RuntimeException} to {@code Throwable}
 * and to cover the sleep call too). An {@code Error} from the duty or a throwing sleeper must
 * therefore be logged at WARN (NFR-O1's "ordinary tick failures") and never stop the loop; only
 * {@code stop()} (a {@code volatile stopping} flag plus an interrupt) may exit it, cleanly and
 * without an abnormal-death log. Because {@code catch (Throwable)} leaves nothing that can
 * realistically escape it, "a truly dead thread is respawned" (FR4) is proven the way the task
 * brief prescribes: by invoking the worker {@code Thread}'s
 * {@code uncaughtExceptionHandler} directly with a synthetic fatal {@link Throwable}, mirroring
 * {@code InstanceHeartbeatLifecycleSpec}'s closest precedent but at the level of the handler's
 * contract rather than forcing an escape from the guarded loop.
 *
 * FR3, FR4 of fix-reaper-idle-liveness; design D4 (D5's backoff progression is task 1.3a's own
 * spec, not asserted here). Together FR3/FR4 are what NFR-R2 ("reaping stays available for the
 * life of the process after any single abnormal reaper fault") reduces to, so this spec is also
 * NFR-R2's implementing evidence.
 */
@Timeout(10)
class StandingReaperResilienceSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)

    private final BlockingSleeper sleeper = new BlockingSleeper()
    private final AtomicInteger ticks = new AtomicInteger()
    // The shared capture helper rather than a hand-rolled ListAppender block (task 2.4 of
    // harden-logging-observability; migrated here because this spec is being touched, per NG5).
    private LogCaptureSupport logs

    def setup() {
        logs = LogCaptureSupport.attach(StandingReaper)
    }

    def cleanup() {
        logs.detach()
    }

    private ReaperDuty countingDuty() {
        { Collection<TaskRef> own -> ticks.incrementAndGet() } as ReaperDuty
    }

    // FR3, D4: an Error escaping the duty on one tick is caught, logged at WARN, and the loop's
    //     very next sleep/tick round still reaps — proving one bad tick cannot end reaping.
    def "an Error from the duty is logged at WARN and the next tick still reaps"() {
        given: 'a duty that throws once, then reaps normally'
        def calls = new AtomicInteger()
        def duty = { Collection<TaskRef> own ->
            if (calls.incrementAndGet() == 1) {
                throw new AssertionError('duty exploded on the first tick' as Object)
            }
            ticks.incrementAndGet()
        } as ReaperDuty
        def reaper = new StandingReaper(duty, sleeper, INTERVAL, {
            []
        }, new SystemClock())

        when: 'the reaper starts, sleeps once, then the first tick throws'
        reaper.start()
        sleeper.awaitEntered()
        sleeper.releaseOne()

        then: 'the throw was logged at WARN and the loop reached a second sleep instead of dying'
        sleeper.awaitEntered()
        calls.get() == 1
        // FR15 of harden-logging-observability: the level alone is not the contract — the catalog
        // code is, so a demotion or a re-worded sentence is caught here.
        logs.list.any {
            it.level == Level.WARN && it.formattedMessage.startsWith(OperatorEvent.STANDING_REAPER_TICK_FAILED.head())
        }

        when: 'that second sleep releases and the following tick runs'
        sleeper.releaseOne()

        then: 'a third sleep entry proves the second, succeeding tick completed'
        sleeper.awaitEntered()
        calls.get() == 2
        ticks.get() == 1

        // stop()'s interrupt reliably wakes the worker parked in this third sleep on its own
        // (proven by "stop() exits the loop cleanly" below), so no further releaseOne() is
        // needed or safe here: once the interrupt cancels the parked take(), a later
        // releaseOne() has no worker left to rendezvous with and blocks forever.
        cleanup:
        reaper.stop()
    }

    // FR3, D4: a sleeper that throws (mimicking an Error surfacing from the interval wait) is
    //     likewise caught and logged, and the loop reaches a further sleep rather than dying.
    def "a throwing sleeper is logged at WARN and the loop still reaches the next tick"() {
        given: 'a sleeper that throws once, then defers to the rendezvous sleeper'
        def base = new BlockingSleeper()
        def calls = new AtomicInteger()
        def flaky = { Duration d ->
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException('sleeper blew up')
            }
            base.sleep(d)
        } as Sleeper
        def reaper = new StandingReaper(countingDuty(), flaky, INTERVAL, {
            []
        }, new SystemClock())

        when: 'the reaper starts; the first sleep call throws before any tick runs'
        reaper.start()

        then: 'the throw was logged at WARN and the loop reached a second sleep call instead of dying'
        base.awaitEntered()
        calls.get() == 2
        ticks.get() == 0
        logs.list.any {
            it.level == Level.WARN && it.formattedMessage.startsWith(OperatorEvent.STANDING_REAPER_TICK_FAILED.head())
        }

        when: 'that sleep releases, letting the tick run'
        base.releaseOne()

        then: 'a third sleep entry proves the tick completed and the loop kept going'
        base.awaitEntered()
        ticks.get() == 1

        // See the analogous cleanup comment above: stop()'s interrupt alone reliably
        // unblocks the parked third sleep; a trailing releaseOne() would deadlock.
        cleanup:
        reaper.stop()
    }

    // FR4, D4: stop() sets the volatile stopping flag and interrupts the worker; the loop exits
    //     cleanly on the next check, and — unlike a genuinely abnormal death — this is never
    //     logged as an ERROR and never triggers a respawn.
    def "stop() exits the loop cleanly with no abnormal-death log"() {
        given:
        def reaper = new StandingReaper(countingDuty(), sleeper, INTERVAL, {
            []
        }, new SystemClock())

        when: 'the reaper starts and parks in its first interval sleep'
        reaper.start()
        sleeper.awaitEntered()
        def worker = reaper.worker()

        and: 'stop() is called while the worker is parked there'
        reaper.stop()
        worker.join(5000)

        then: 'the worker terminated cleanly, having never ticked and never logged an abnormal death'
        !worker.isAlive()
        ticks.get() == 0
        logs.list.every { it.level != Level.ERROR }
    }

    // FR4, D4, D5: because catch (Throwable) leaves no realistic escape from the guarded loop, a
    //     "truly dead thread" is modeled — as the task brief prescribes — by invoking the
    //     worker's uncaughtExceptionHandler directly with a synthetic fatal Throwable. Absent a
    //     prior stop(), that handler must back off (design D5, on the same injected sleeper)
    //     before it respawns a fresh, distinct, live worker — proven here by driving the extra
    //     backoff-sleep rendezvous round before the fresh worker's own first interval sleep; the
    //     backoff progression itself is StandingReaperSupervisionSpec's job, not this one's.
    def "a truly dead thread is respawned when stop() was not called"() {
        given:
        def reaper = new StandingReaper(countingDuty(), sleeper, INTERVAL, {
            []
        }, new SystemClock())
        reaper.start()
        sleeper.awaitEntered()
        def deadWorker = reaper.worker()
        def handler = deadWorker.uncaughtExceptionHandler

        when: 'the handler fires on its own thread, as if the worker escaped even the Throwable guard'
        Thread.ofVirtual().start {
            handler.uncaughtException(deadWorker, new OutOfMemoryError('simulated unrecoverable death'))
        }

        then: 'the handler parks in its backoff wait before respawning'
        sleeper.awaitEntered() == INTERVAL

        when: 'the backoff wait releases'
        sleeper.releaseOne()

        then: 'a fresh, distinct worker took over and reached its own first sleep'
        sleeper.awaitEntered()
        !reaper.worker().is(deadWorker)

        cleanup:
        reaper.stop()
    }

    // FR15 of harden-logging-observability: the backoff sleep before a respawn is itself guarded
    //     (design D5), and the guard's WARN is the only trace that a respawn skipped its backoff —
    //     a reaper respawning in a tight loop with no delay looks identical without it.
    def "a backoff sleep that throws before the respawn is logged at WARN and the respawn still happens"() {
        given: 'a sleeper that throws on the backoff call only — the loop sleeps normally'
        def base = new BlockingSleeper()
        def calls = new AtomicInteger()
        def flaky = { Duration d ->
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException('backoff sleep blew up')
            }
            base.sleep(d)
        } as Sleeper
        def reaper = new StandingReaper(countingDuty(), flaky, INTERVAL, {
            []
        }, new SystemClock())
        reaper.start()
        base.awaitEntered()
        def deadWorker = reaper.worker()

        when: 'the worker dies and the handler backs off on the throwing sleeper'
        Thread.ofVirtual().start {
            deadWorker.uncaughtExceptionHandler.uncaughtException(deadWorker, new OutOfMemoryError('simulated death'))
        }

        then: 'a fresh worker still took over, reaching its own first interval sleep'
        base.awaitEntered()
        !reaper.worker().is(deadWorker)

        and:
        logs.list.any {
            it.level == Level.WARN &&
            it.formattedMessage.startsWith(OperatorEvent.STANDING_REAPER_BACKOFF_SLEEP_FAILED.head())
        }

        cleanup:
        reaper.stop()
    }

    // FR4, D4: if stopping was already set before the handler fires — the intentional-shutdown
    //     race the design calls out — no respawn happens: the worker reference is unchanged.
    def "a dead thread is not respawned once stop() was already called"() {
        given:
        def reaper = new StandingReaper(countingDuty(), sleeper, INTERVAL, {
            []
        }, new SystemClock())
        reaper.start()
        sleeper.awaitEntered()
        def worker = reaper.worker()
        def handler = worker.uncaughtExceptionHandler

        when: 'stop() is called, then the handler fires as if death raced the shutdown'
        reaper.stop()
        handler.uncaughtException(worker, new OutOfMemoryError('simulated death racing stop()'))

        then: 'no respawn happened: the worker reference is unchanged'
        reaper.worker().is(worker)
    }
}
