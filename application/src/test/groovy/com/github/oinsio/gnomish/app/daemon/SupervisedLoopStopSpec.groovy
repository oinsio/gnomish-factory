package com.github.oinsio.gnomish.app.daemon

import ch.qos.logback.classic.Level
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Stopping and joining the supervised loop (design D4 of
 * supervise-daemon-loops-and-embed-dashboard): a stop never interrupts a tick, start never leaks a
 * worker, stops are idempotent, and a joining stop waits out the tick in progress. The races a stop
 * runs against a respawn are {@code SupervisedLoopStopConcurrencySpec}'s subject.
 */
@Timeout(10)
class SupervisedLoopStopSpec extends Specification {

    private final SupervisedLoopHarness rig = new SupervisedLoopHarness(Level.INFO)

    def cleanup() {
        rig.close()
    }

    // FR4 (daemon-supervision "Stop during a run lets the run finish without a warning").
    def "a stop requested during a tick does not interrupt it, and no wait or tick follows"() {
        given:
        def interruptedAfterStop = null
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait()) { n ->
            rig.stopHere()
            interruptedAfterStop = Thread.currentThread().isInterrupted()
        }

        when:
        rig.runToStop()

        then:
        rig.journal == ['tick1']
        interruptedAfterStop == false
        rig.atLevel(Level.WARN).isEmpty()
    }

    // FR4, NFR-R2: start never leaks a second worker, and never starts one after a stop.
    def "start is a no-op after a stop"() {
        given:
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait()) { n -> }

        when:
        rig.loop.stop()
        rig.loop.start()
        rig.stopAndJoinWithinBound()

        then:
        rig.ticks.get() == 0
    }

    def "a second start while the worker runs spawns no second worker"() {
        given:
        def entered = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait()) { n ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            rig.stopHere()
        }
        rig.loop.start()
        entered.await(5, TimeUnit.SECONDS)

        when:
        rig.loop.start()
        release.countDown()
        rig.stopAndJoinWithinBound()

        then:
        rig.ticks.get() == 1
    }

    // FR4: stopping is idempotent, and a joining stop of a loop never started returns at once.
    def "stop and stopAndJoin on a loop never started return at once"() {
        given:
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait()) { n -> }

        when:
        rig.loop.stop()
        rig.loop.stop()
        boolean joined = rig.stopAndJoinWithinBound()

        then:
        joined
        rig.ticks.get() == 0
        rig.loop.restartCount() == 0
    }

    // FR4, NFR-R2 (design D4): a joining stop returns only once the tick in progress completed.
    def "a joining stop waits out the tick in progress"() {
        given:
        def entered = new CountDownLatch(1)
        boolean finished = false
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait()) { n ->
            entered.countDown()
            Thread.sleep(200)
            finished = true
        }
        rig.loop.start()
        entered.await(5, TimeUnit.SECONDS)

        when:
        boolean joined = rig.stopAndJoinWithinBound()

        then:
        joined
        finished
        rig.ticks.get() == 1
    }

    // FR4: a caller interrupted while joining stops waiting and keeps its interrupt.
    def "an interrupted joining stop returns and keeps the caller's interrupt flag"() {
        given:
        def entered = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait()) { n ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        rig.loop.start()
        entered.await(5, TimeUnit.SECONDS)

        when:
        Thread.currentThread().interrupt()
        rig.loop.stopAndJoin()
        boolean keptFlag = Thread.interrupted()
        release.countDown()

        then:
        keptFlag
    }
}
