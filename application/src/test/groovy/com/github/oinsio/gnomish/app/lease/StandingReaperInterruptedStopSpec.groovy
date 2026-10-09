package com.github.oinsio.gnomish.app.lease

import static com.github.oinsio.gnomish.app.lease.ReaperLoopRig.BOUND
import static com.github.oinsio.gnomish.app.lease.ReaperLoopRig.Unrenderable

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The observed stop of the standing reaper, end to end on a real thread (FR12, NFR-O2, M5 of
 * fix-operator-blockers), under the supervised loop's stop (FR6 of
 * supervise-daemon-loops-and-embed-dashboard, design D4): a stop cuts short the interval wait and
 * a restart backoff, but never a tick. An idle daemon's reaper spends its tick waiting on the
 * tracker's open listing; a stop that finds it there no longer interrupts the listing — it
 * completes, and the loop ends at the check after it. Either way the stop is quiet: no WARN or
 * ERROR from the reaper or its loop, and no tick after it.
 *
 * <p>Rewritten from the interrupt-mid-listing feature of fix-operator-blockers, whose premise
 * (the stop interrupts the listing) design D4 retired; the interrupt classification at the
 * reaper's tracker sites is still pinned by {@code ReaperInterruptSpec}.
 *
 * Implements FR12, NFR-O2 of fix-operator-blockers; FR6 of supervise-daemon-loops-and-embed-dashboard.
 */
@Timeout(10)
class StandingReaperInterruptedStopSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)

    private final LogCaptureSupport reaperLogs = LogCaptureSupport.attach(Reaper)
    private final ReaperLoopRig rig = new ReaperLoopRig()

    def cleanup() {
        rig.close()
        reaperLogs.detach()
    }

    private boolean quiet() {
        (reaperLogs.list + rig.snapshot()).every {
            !it.level.isGreaterOrEqual(Level.WARN)
        }
    }

    /** A sleeper that blocks until released or interrupted, restoring the flag as production does. */
    private static final class LatchedSleeper implements Sleeper {
        final CountDownLatch entered = new CountDownLatch(1)
        final CountDownLatch release = new CountDownLatch(1)
        final CountDownLatch returned = new CountDownLatch(1)

        @Override
        void sleep(Duration duration) {
            entered.countDown()
            try {
                release.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt()
            } finally {
                returned.countDown()
            }
        }
    }

    // FR12, NFR-O2 of fix-operator-blockers; FR6, D4: a stop while the tick waits on the tracker's
    //     listing leaves the listing uninterrupted; it completes, nothing warns, nothing follows.
    def "a stop while the sweep listing waits on the tracker lets it finish quietly"() {
        given: 'a tracker whose open listing blocks until released, recording an interrupt'
        def listing = new CountDownLatch(1)
        def finishListing = new CountDownLatch(1)
        boolean listingInterrupted = false
        def tracker = Stub(Tracker) {
            listOpen() >> {
                listing.countDown()
                try {
                    finishListing.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
                } catch (InterruptedException e) {
                    listingInterrupted = true
                    Thread.currentThread().interrupt()
                }
                []
            }
        }
        def reaper = new Reaper(tracker, new StalenessMemory(new VirtualMonotonicTime(), INTERVAL))
        def ticks = 0
        def standing = new StandingReaper({ own ->
            ticks++
            reaper.reapOnce(own)
        } as ReaperDuty, INTERVAL, {
            []
        }, VirtualTimeEquipment.on(new VirtualClock(), { Duration d -> } as Sleeper))
        rig.reaper = standing

        when: 'the first tick parks inside listOpen, and the daemon stops'
        standing.start()
        assert listing.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
        standing.stop()
        finishListing.countDown()

        then:
        rig.joinWithinBound()
        !listingInterrupted
        ticks == 1
        quiet()
    }

    // FR4 of fix-reaper-idle-liveness; FR6, D4: a stop cuts the interval wait short — the reaper
    //     ends without ever ticking, quietly.
    def "a stop during the interval wait ends the reaper without a tick, quietly"() {
        given:
        def sleeper = new LatchedSleeper()
        def standing = new StandingReaper(ReaperDuty.NONE, INTERVAL, {
            []
        }, VirtualTimeEquipment.on(new VirtualClock(), sleeper))
        rig.reaper = standing

        when:
        standing.start()
        assert sleeper.entered.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
        standing.stop()

        then:
        sleeper.returned.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
        rig.joinWithinBound()
        quiet()
    }

    // FR6, D4: the joining stop the lifecycle specs rely on is itself a stop — it cuts the wait
    //     short and returns only once the reaper's thread has left it.
    def "a joining stop during the interval wait returns only after the wait ended"() {
        given:
        def sleeper = new LatchedSleeper()
        def standing = new StandingReaper(ReaperDuty.NONE, INTERVAL, {
            []
        }, VirtualTimeEquipment.on(new VirtualClock(), sleeper))
        rig.reaper = standing

        when:
        standing.start()
        assert sleeper.entered.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
        boolean joined = rig.joinWithinBound()

        then:
        joined
        sleeper.returned.count == 0
        quiet()
    }

    // FR4 of fix-reaper-idle-liveness; FR6, D4: a stop during a restart backoff cuts it short and
    //     no thread is respawned — no tick follows the death.
    def "a stop during the restart backoff spawns no thread"() {
        given: 'the first sleep is the interval wait, the second the backoff after a death'
        def backoff = new LatchedSleeper()
        rig.build(INTERVAL, { n -> throw new Unrenderable() }, { n, d ->
            if (n == 2) backoff.sleep(d)
        })

        when:
        rig.reaper.start()
        assert backoff.entered.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
        rig.reaper.stop()

        then:
        backoff.returned.await(BOUND.toMillis(), TimeUnit.MILLISECONDS)
        rig.joinWithinBound()
        rig.ticks.get() == 1
        rig.reaper.restartCount() == 1
    }
}
