package com.github.oinsio.gnomish.app.lease

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.util.concurrent.CountDownLatch
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The observed stop, end to end on a real thread (FR12, NFR-O2, M5 of fix-operator-blockers; U5,
 * G4): an idle daemon's standing reaper spends its tick waiting on the tracker's open listing, so
 * that is where a signal stop finds it. The real {@link Reaper} runs inside the real {@link
 * StandingReaper} worker against a tracker whose {@code listOpen} blocks until interrupted and then
 * fails the way the GitHub adapter does — interrupt left set, a failure that is not an outage.
 * {@code stop()} must end the worker with no WARN or ERROR from either class: the stop is not a
 * sweep-listing failure, and not a tick failure.
 *
 * <p>Kept apart from {@code StandingReaperResilienceSpec}, whose features drive a counting duty:
 * this one needs the real reaper and a blocking tracker, a different fixture for a different
 * property.
 *
 * Implements FR12, NFR-O2 of fix-operator-blockers.
 */
@Timeout(10)
class StandingReaperInterruptedStopSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)

    private final BlockingSleeper sleeper = new BlockingSleeper()
    private final CountDownLatch listing = new CountDownLatch(1)
    private final LogCaptureSupport reaperLogs = LogCaptureSupport.attach(Reaper)
    private final LogCaptureSupport workerLogs = LogCaptureSupport.attach(StandingReaper)

    def cleanup() {
        reaperLogs.detach()
        workerLogs.detach()
    }

    /** A tracker whose open listing waits on GitHub forever and honours the interrupt as it does. */
    private Tracker blockingTracker() {
        Stub(Tracker) {
            listOpen() >> {
                listing.countDown()
                try {
                    new CountDownLatch(1).await()
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt()
                    throw new IllegalStateException('call cancelled', e)
                }
                []
            }
        }
    }

    // FR12, NFR-O2, M5: stop() while the tick waits on the listing — the worker ends, nothing warns.
    def "stopping the reaper while its sweep listing waits on the tracker is quiet"() {
        given:
        def reaper = new Reaper(blockingTracker(), new StalenessMemory(new VirtualMonotonicTime(), INTERVAL))
        def standing = new StandingReaper(reaper, sleeper, INTERVAL, {
            []
        }, new SystemClock())

        when: 'the worker sleeps once, then its tick parks inside listOpen'
        standing.start()
        sleeper.awaitEntered()
        def worker = standing.worker()
        sleeper.releaseOne()
        listing.await()

        and: 'the daemon stops'
        standing.stop()
        worker.join(5000)

        then:
        !worker.isAlive()
        (reaperLogs.list + workerLogs.list).every {
            !it.level.isGreaterOrEqual(Level.WARN)
        }
    }
}
