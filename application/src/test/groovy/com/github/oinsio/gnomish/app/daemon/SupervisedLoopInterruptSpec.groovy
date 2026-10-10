package com.github.oinsio.gnomish.app.daemon

import static com.github.oinsio.gnomish.app.daemon.SupervisedLoopHarness.INTERVAL

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import java.time.Duration
import spock.lang.Specification
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * Interrupts of the supervised loop (design D3 of supervise-daemon-loops-and-embed-dashboard): a
 * stray interrupt is cleared and logged once and never buys a tick without a full wait, while the
 * interrupt a stop delivers to a wait is quiet.
 */
@Timeout(10)
class SupervisedLoopInterruptSpec extends Specification {

    private final SupervisedLoopHarness rig = new SupervisedLoopHarness(Level.INFO)

    def cleanup() {
        rig.close()
    }

    // FR5 (daemon-supervision "Stray interrupt"): an interrupt during a tick is cleared and logged
    //     once, and the wait after the tick runs with the flag clear, in full.
    def "a stray interrupt during a tick is logged once and the next tick still follows a full wait"() {
        given:
        def flagAtWait = []
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait {
            flagAtWait << Thread.currentThread().isInterrupted()
        }) { n ->
            if (n == 1) Thread.currentThread().interrupt()
            else rig.stopHere()
        }

        when:
        rig.runToStop()

        then:
        rig.journal == [
            'tick1',
            "wait ${INTERVAL}",
            'tick2'
        ]*.toString()
        flagAtWait == [false]
        def strays = rig.events(OperatorEvent.DAEMON_LOOP_STRAY_INTERRUPT)
        strays.size() == 1
        strays[0].level == Level.WARN
        strays[0].MDCPropertyMap['component'] == 'janitor'
    }

    // FR5: a sleeper cut short by a stray interrupt returns early with the flag set; the loop waits
    //     again in full rather than ticking early.
    def "a wait cut short by a stray interrupt is waited again in full before the tick"() {
        given:
        def wait = rig.fixedWait { n ->
            if (n == 1) Thread.currentThread().interrupt()
        }
        rig.build(LoopOrder.WAIT_THEN_TICK, wait) { n -> rig.stopHere() }

        when:
        rig.runToStop()

        then:
        rig.journal == [
            "wait ${INTERVAL}",
            "wait ${INTERVAL}",
            'tick1'
        ]*.toString()
        rig.events(OperatorEvent.DAEMON_LOOP_STRAY_INTERRUPT).size() == 1
    }

    // FR5: the signal wait turns an interrupt into the same set flag, handled by the same check.
    def "a stray interrupt during a signal wait is logged and the loop keeps waiting"() {
        given:
        Thread worker = null
        rig.build(LoopOrder.TICK_THEN_WAIT, new LoopWait.IntervalOrSignal(Duration.ofHours(1))) { n ->
            worker = Thread.currentThread()
        }
        def conditions = new PollingConditions(timeout: 2)
        rig.loop.start()
        conditions.eventually { assert rig.loop.waiting() }

        when:
        worker.interrupt()

        then:
        conditions.eventually {
            assert rig.events(OperatorEvent.DAEMON_LOOP_STRAY_INTERRUPT).size() == 1
        }
        conditions.eventually { assert rig.loop.waiting() }
        rig.ticks.get() == 1
    }

    // FR4, FR5 (daemon-supervision "Interrupt from a stop is quiet"): a stop arriving during the
    //     wait interrupts it, and the loop ends without a stray-interrupt WARN or another tick.
    def "a stop requested during the wait ends the loop with no warning"() {
        given:
        rig.build(LoopOrder.WAIT_THEN_TICK, rig.fixedWait {
            rig.stopHere()
        }) { n -> }

        when:
        rig.runToStop()

        then:
        rig.journal == ["wait ${INTERVAL}"]*.toString()
        rig.atLevel(Level.WARN).isEmpty() && rig.atLevel(Level.ERROR).isEmpty()
    }
}
