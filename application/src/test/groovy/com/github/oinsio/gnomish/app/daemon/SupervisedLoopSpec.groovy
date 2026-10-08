package com.github.oinsio.gnomish.app.daemon

import static com.github.oinsio.gnomish.app.daemon.SupervisedLoopHarness.INTERVAL

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import java.time.Duration
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The supervised loop's cycle and its level-1 guard (design D1, D2 of
 * supervise-daemon-loops-and-embed-dashboard): both orders, the {@code Throwable} guard around the
 * tick and the wait, and the failure edges it logs. Virtual time: the sleepers journal instead of
 * sleeping. The wait kinds are {@code SupervisedLoopWaitSpec}'s subject.
 */
@Timeout(10)
class SupervisedLoopSpec extends Specification {

    private final SupervisedLoopHarness rig = new SupervisedLoopHarness(Level.DEBUG)

    def cleanup() {
        rig.close()
    }

    // FR1: tick then wait — the first tick runs at once, every later one after a full interval.
    def "a tick-first loop ticks at once and then waits a full interval before each tick"() {
        given:
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait()) { n ->
            if (n == 3) rig.stopHere()
        }

        when:
        rig.runToStop()

        then:
        rig.journal == [
            'tick1',
            "wait ${INTERVAL}",
            'tick2',
            "wait ${INTERVAL}",
            'tick3'
        ]*.toString()
        rig.atLevel(Level.WARN).isEmpty() && rig.atLevel(Level.ERROR).isEmpty()
    }

    // FR1: wait then tick — a full interval passes before the very first tick.
    def "a wait-first loop waits a full interval before its first tick"() {
        given:
        rig.build(LoopOrder.WAIT_THEN_TICK, rig.fixedWait()) { n ->
            if (n == 2) rig.stopHere()
        }

        when:
        rig.runToStop()

        then:
        rig.journal == [
            "wait ${INTERVAL}",
            'tick1',
            "wait ${INTERVAL}",
            'tick2'
        ]*.toString()
    }

    // FR2: an Error from the tick is a WARN with the component's code and key; the loop goes on to
    //     its wait and ticks again, and the clean tick announces the recovery.
    def "an Error thrown by the tick is logged and the loop waits and ticks again"() {
        given:
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait()) { n ->
            if (n == 1) throw new Error('tick broke')
            rig.stopHere()
        }

        when:
        rig.runToStop()

        then:
        rig.journal == [
            'tick1',
            "wait ${INTERVAL}",
            'tick2'
        ]*.toString()
        def warn = rig.events(OperatorEvent.DAEMON_LOOP_TICK_FAILED).first()
        warn.level == Level.WARN
        warn.formattedMessage.contains('tick failed: java.lang.Error: tick broke')
        warn.throwableProxy.className == 'java.lang.Error'
        warn.MDCPropertyMap['component'] == 'janitor'
        rig.atLevel(Level.INFO)*.formattedMessage.any {
            it.contains('recovered after 1 failure')
        }
    }

    // FR2 (daemon-supervision "A throwing wait does not end the loop").
    def "an Error thrown by the wait is logged and the loop ticks again"() {
        given:
        def wait = rig.fixedWait { n ->
            if (n == 1) throw new Error('sleep broke')
        }
        rig.build(LoopOrder.WAIT_THEN_TICK, wait) { n -> rig.stopHere() }

        when:
        rig.runToStop()

        then:
        rig.journal == ["wait ${INTERVAL}", 'tick1']*.toString()
        def warns = rig.events(OperatorEvent.DAEMON_LOOP_TICK_FAILED)
        warns.size() == 1
        warns[0].formattedMessage.contains('wait failed: java.lang.Error: sleep broke')
    }

    // FR2, NFR-O1 (daemon-supervision "Repeated failures of a loop log edges"): one WARN, DEBUG
    //     repeats, one INFO recovery carrying the streak's count.
    def "repeated tick failures log one WARN, then DEBUG repeats, then one INFO recovery"() {
        given:
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait()) { n ->
            if (n <= 4) throw new IllegalStateException('tracker down')
            if (n == 6) rig.stopHere()
        }

        when:
        rig.runToStop()

        then:
        rig.events(OperatorEvent.DAEMON_LOOP_TICK_FAILED)*.formattedMessage.every {
            it.contains('(1x so far)')
        }
        rig.atLevel(Level.WARN).size() == 1
        rig.atLevel(Level.DEBUG)*.formattedMessage.findAll {
            it.contains('still failing')
        }.size() == 3
        rig.atLevel(Level.INFO)*.formattedMessage == [
            'daemon loop recovered after 4 failure(s) over PT0S: last was tick failed: ' +
            'java.lang.IllegalStateException: tracker down'
        ]
    }

    // FR2, NFR-O1: the suppressor's periodic roll-up reaches the console at WARN with its count.
    def "a failure streak outlasting the roll-up interval is reminded at WARN with its count"() {
        given:
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait()) { n ->
            if (n == 2) rig.suppressorClock.advance(Duration.ofMinutes(6))
            if (n <= 2) throw new IllegalStateException('tracker down')
            rig.stopHere()
        }

        when:
        rig.runToStop()

        then:
        def warns = rig.events(OperatorEvent.DAEMON_LOOP_TICK_FAILED)*.formattedMessage
        warns.size() == 2
        warns[1].contains('(2x so far)')
    }
}
