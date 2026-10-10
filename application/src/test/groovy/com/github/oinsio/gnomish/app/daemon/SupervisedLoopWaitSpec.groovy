package com.github.oinsio.gnomish.app.daemon

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.fake.InterruptOnlySleeper
import java.time.Duration
import spock.lang.Specification
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * The two wait kinds of the supervised loop (design D1, D4 of
 * supervise-daemon-loops-and-embed-dashboard): the signal wait's permit coalescing and its
 * interval, and a stop cutting each kind short — an interrupt for a sleeper, a signal for the
 * signal wait. Real time only where a long wait is the subject a stop must cut short.
 */
@Timeout(10)
class SupervisedLoopWaitSpec extends Specification {

    private final SupervisedLoopHarness rig = new SupervisedLoopHarness(Level.INFO)

    def cleanup() {
        rig.close()
    }

    // FR1 (daemon-supervision "Wake signals coalesce"): any number of signals during a tick buy
    //     exactly one more tick, and none are left over to shorten the wait after it.
    def "signals landing during a tick coalesce into one more tick"() {
        given:
        def wait = new LoopWait.IntervalOrSignal(Duration.ofHours(1))
        int pendingInTick = -1
        int leftOver = -1
        rig.build(LoopOrder.TICK_THEN_WAIT, wait) { n ->
            if (n == 1) {
                5.times { wait.signal() }
                pendingInTick = wait.pendingSignals()
            } else {
                leftOver = wait.pendingSignals()
                rig.stopHere()
            }
        }

        when:
        rig.runToStop()

        then:
        rig.ticks.get() == 2
        pendingInTick == 5
        leftOver == 0
    }

    // FR1: with no signal, the interval elapsing is what brings the next tick.
    def "a signal wait with no signal ticks again once its interval elapses"() {
        given:
        rig.build(LoopOrder.TICK_THEN_WAIT, new LoopWait.IntervalOrSignal(Duration.ofMillis(5))) { n ->
            if (n == 2) rig.stopHere()
        }

        when:
        rig.runToStop()

        then:
        rig.ticks.get() == 2
    }

    // FR4: a stop cuts each kind of wait short — an interrupt for a sleeper, a signal for the
    //     signal wait — and ends the loop with no WARN or ERROR (an interrupt from a stop is quiet).
    def "a stop during a long #kind wait ends the loop promptly and quietly"() {
        given:
        rig.build(LoopOrder.WAIT_THEN_TICK, wait) { n -> }
        rig.loop.start()
        new PollingConditions(timeout: 2).eventually {
            assert rig.loop.waiting()
        }

        when:
        boolean joined = rig.stopAndJoinWithinBound()

        then:
        joined
        rig.ticks.get() == 0
        !rig.loop.waiting()
        rig.atLevel(Level.WARN).isEmpty() && rig.atLevel(Level.ERROR).isEmpty()

        where:
        kind | wait
        'sleeper' | new LoopWait.FixedInterval(new InterruptOnlySleeper(), Duration.ofHours(1))
        'signal' | new LoopWait.IntervalOrSignal(Duration.ofHours(1))
    }
}
