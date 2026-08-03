package com.github.oinsio.gnomish.app.lease

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import java.time.Duration
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The supervised-restart policy (design D5, task 1.3a): the exponential backoff before a
 * respawn, its reset on a clean tick, the monotonically increasing restart counter, and the
 * ERROR-level respawn log (NFR-O1, UX2). Unlike {@code StandingReaperResilienceSpec} (which
 * proves the plain "a dead worker is respawned" contract of D4), these specs drive the backoff
 * wait itself through the injected {@link BlockingSleeper} rendezvous — no real sleeping, no
 * wall-clock timing assertions — by invoking the worker's {@code uncaughtExceptionHandler} on a
 * background thread (the handler now blocks on the backoff sleep, so it cannot run on the test
 * thread without deadlocking the rendezvous) and driving {@code awaitEntered}/{@code releaseOne}
 * for both the backoff wait and the fresh worker's own interval sleep.
 *
 * FR4 of fix-reaper-idle-liveness; design D5.
 */
@Timeout(10)
class StandingReaperSupervisionSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(1)
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10)

    private final BlockingSleeper sleeper = new BlockingSleeper()
    private final Logger logbackLogger = (Logger) LoggerFactory.getLogger(StandingReaper)
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>()

    def setup() {
        appender.start()
        logbackLogger.addAppender(appender)
    }

    def cleanup() {
        logbackLogger.detachAppender(appender)
        appender.stop()
    }

    private static ReaperDuty countingDuty() {
        { Collection<TaskRef> own -> } as ReaperDuty
    }

    // Kills the given worker on a background thread and drives the backoff-wait rendezvous,
    // returning the backoff duration the handler asked for. Does NOT drive the fresh worker's
    // own post-respawn interval sleep — callers that need the new worker do that themselves.
    private Duration killAndAwaitBackoff(Thread worker) {
        def handler = worker.uncaughtExceptionHandler
        Thread.ofVirtual().start {
            handler.uncaughtException(worker, new OutOfMemoryError('simulated unrecoverable death'))
        }
        sleeper.awaitEntered()
    }

    // FR4, D5: consecutive deaths (no clean tick between them) double the backoff each time,
    //     starting at the base interval, until the cap stops further growth.
    def "consecutive deaths double the backoff up to the 10-minute cap"() {
        given: 'a reaper parked in its first interval sleep'
        def reaper = new StandingReaper(countingDuty(), sleeper, INTERVAL, { [] })
        reaper.start()
        sleeper.awaitEntered()

        expect: 'each consecutive death backs off double the previous, capped at 10 minutes'
        for (expectedBackoff in [
                    INTERVAL,
                    INTERVAL.multipliedBy(2),
                    INTERVAL.multipliedBy(4),
                    INTERVAL.multipliedBy(8),
                    MAX_BACKOFF
                ]) {
            def dying = reaper.worker()
            def backoff = killAndAwaitBackoff(dying)
            assert backoff == expectedBackoff

            sleeper.releaseOne()
            sleeper.awaitEntered()
            assert !reaper.worker().is(dying)
        }

        cleanup:
        reaper.stop()
    }

    // FR4, D5: once a respawned worker completes one full tick without dying, the backoff
    //     resets — a later death again starts from the base interval instead of continuing to
    //     double from where it left off.
    def "a clean tick after a respawn resets the backoff to the base interval"() {
        given: 'a reaper whose first worker has already died twice in a row (backoff at 2x)'
        def reaper = new StandingReaper(countingDuty(), sleeper, INTERVAL, { [] })
        reaper.start()
        sleeper.awaitEntered()

        def firstDying = reaper.worker()
        assert killAndAwaitBackoff(firstDying) == INTERVAL
        sleeper.releaseOne()
        sleeper.awaitEntered()

        def secondDying = reaper.worker()
        assert killAndAwaitBackoff(secondDying) == INTERVAL.multipliedBy(2)
        sleeper.releaseOne()
        sleeper.awaitEntered()

        when: 'the third worker completes one full clean tick'
        def cleanWorker = reaper.worker()
        sleeper.releaseOne()
        sleeper.awaitEntered()

        and: 'that same worker then dies'
        def backoff = killAndAwaitBackoff(cleanWorker)

        then: 'the backoff restarted from the base interval, not from 4x'
        backoff == INTERVAL

        cleanup:
        reaper.stop()
    }

    // FR4, NFR-O1, UX2: the ERROR log line for each respawn carries a monotonically increasing
    //     restart count, the only surface a persistent fault is visible through.
    def "each respawn logs an ERROR line with a monotonically increasing restart count"() {
        given:
        def reaper = new StandingReaper(countingDuty(), sleeper, INTERVAL, { [] })
        reaper.start()
        sleeper.awaitEntered()

        when: 'three consecutive deaths are driven through respawn'
        3.times {
            def dying = reaper.worker()
            killAndAwaitBackoff(dying)
            sleeper.releaseOne()
            sleeper.awaitEntered()
        }

        then: 'the ERROR lines carry restart counts 1, 2, 3 in order'
        def errorMessages = appender.list.findAll { it.level == Level.ERROR }*.formattedMessage
        errorMessages.size() == 3
        errorMessages[0].contains('restart #1')
        errorMessages[1].contains('restart #2')
        errorMessages[2].contains('restart #3')

        cleanup:
        reaper.stop()
    }

    // FR1, D4: start() is idempotent — a second call while a worker already runs is a no-op and
    //     never leaks a second ticking worker (the first would otherwise be overwritten and leak).
    def "a second start() while a worker already runs is a no-op"() {
        given: 'a reaper whose worker is parked in its first interval sleep'
        def reaper = new StandingReaper(countingDuty(), sleeper, INTERVAL, { [] })
        reaper.start()
        sleeper.awaitEntered()
        def firstWorker = reaper.worker()

        when: 'start() is called again'
        reaper.start()

        then: 'the worker reference is unchanged — no second worker was spawned'
        reaper.worker().is(firstWorker)

        cleanup:
        reaper.stop()
    }

    // FR4, D5: stop() racing the backoff wait (not just the interval wait) must not respawn —
    //     the death-handling thread checks stopping again once the backoff sleep returns.
    def "stop() during the backoff wait does not respawn"() {
        given:
        def reaper = new StandingReaper(countingDuty(), sleeper, INTERVAL, { [] })
        reaper.start()
        sleeper.awaitEntered()
        def dyingWorker = reaper.worker()
        def handler = dyingWorker.uncaughtExceptionHandler

        when: 'the handler fires and parks in its backoff wait'
        def deathThread = Thread.ofVirtual().start {
            handler.uncaughtException(dyingWorker, new OutOfMemoryError('simulated death'))
        }
        sleeper.awaitEntered()

        and: 'stop() is called while parked there, then the backoff wait releases'
        reaper.stop()
        sleeper.releaseOne()
        deathThread.join(5000)

        then: 'no respawn happened: the worker reference is unchanged'
        reaper.worker().is(dyingWorker)
    }
}
