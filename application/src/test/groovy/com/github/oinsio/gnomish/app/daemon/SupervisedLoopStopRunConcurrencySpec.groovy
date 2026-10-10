package com.github.oinsio.gnomish.app.daemon

import static com.github.oinsio.gnomish.app.daemon.SupervisedLoopHarness.JOIN_BOUND

import ch.qos.logback.classic.Level
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.Timeout

/**
 * A stop arriving from another thread while the supervised loop runs its tick or sits in its wait
 * (design D4 of supervise-daemon-loops-and-embed-dashboard), on real threads per
 * {@code lock-scope.md} "Specs": the tick is never interrupted and finishes quietly, and the wait
 * is cut short at once. The races a stop runs against a respawn are
 * {@code SupervisedLoopStopConcurrencySpec}'s subject.
 */
@Timeout(20)
class SupervisedLoopStopRunConcurrencySpec extends Specification {

    private static final Duration PROMPT = Duration.ofMillis(500)

    private final SupervisedLoopHarness rig = new SupervisedLoopHarness(Level.INFO)

    def cleanup() {
        rig.close()
    }

    private static boolean onHelper(Duration bound, Closure action) {
        Thread.ofVirtual().start(action).join(bound)
    }

    private List<Level> warnOrWorse() {
        rig.snapshot()*.level.findAll { it.isGreaterOrEqual(Level.WARN) }
    }

    // FR4, NFR-R2 (daemon-supervision "Stop during a run lets the run finish without a warning").
    def "a stop during a tick blocked on a latch lets it finish uninterrupted, quietly, as the last"() {
        given:
        def wait = new LatchedSleeper(true)
        def ticking = new CountDownLatch(1)
        def finishTick = new CountDownLatch(1)
        def sawInterrupt = null
        boolean completed = false
        rig.build(LoopOrder.TICK_THEN_WAIT, new LoopWait.FixedInterval(wait, SupervisedLoopHarness.INTERVAL)) { n ->
            ticking.countDown()
            finishTick.await(JOIN_BOUND.toMillis(), TimeUnit.MILLISECONDS)
            sawInterrupt = Thread.currentThread().isInterrupted()
            completed = true
        }
        rig.loop.start()
        assert ticking.await(JOIN_BOUND.toMillis(), TimeUnit.MILLISECONDS)

        when:
        boolean prompt = onHelper(PROMPT) { rig.loop.stop() }
        finishTick.countDown()
        boolean joined = rig.stopAndJoinWithinBound()

        then:
        prompt
        joined
        completed
        sawInterrupt == false
        rig.ticks.get() == 1
        wait.entered.count == 1
        warnOrWorse().isEmpty()
    }

    // FR4, NFR-R2 (design D4): a stop cuts short a wait of either shape at once, without a warning.
    def "a stop during the #shape wait ends it promptly and no tick follows"() {
        given:
        def sleeper = new LatchedSleeper(true)
        def loopWait = shape == 'fixed'
                ? new LoopWait.FixedInterval(sleeper, Duration.ofHours(1))
                : new LoopWait.IntervalOrSignal(Duration.ofHours(1))
        def ticked = new CountDownLatch(1)
        rig.build(LoopOrder.TICK_THEN_WAIT, loopWait) { n ->
            ticked.countDown()
        }
        rig.loop.start()
        assert ticked.await(JOIN_BOUND.toMillis(), TimeUnit.MILLISECONDS)
        assert awaitWaiting()

        when:
        boolean prompt = onHelper(PROMPT) { rig.loop.stop() }
        boolean joined = rig.stopAndJoinWithinBound()

        then:
        prompt
        joined
        rig.ticks.get() == 1
        sleeper.interrupted == (shape == 'fixed')
        warnOrWorse().isEmpty()

        where:
        shape << ['fixed', 'signal']
    }

    // Spins on the package-private probe with a bounded deadline: the worker is inside its wait.
    private boolean awaitWaiting() {
        long deadline = System.nanoTime() + JOIN_BOUND.toNanos()
        while (!rig.loop.waiting()) {
            if (System.nanoTime() > deadline) {
                return false
            }
            Thread.onSpinWait()
        }
        true
    }
}
