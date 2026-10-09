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
            if (n == 2) rig.clock.advance(Duration.ofMinutes(6))
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

    // FR2, NFR-O1 (daemon-supervision "The roll-up period outlives the loop's own interval"): a
    //     5 min loop failing for an hour rolls up every 30 min (six ticks), never once per tick —
    //     on the catalog's 5 min default every one of its twelve failures would have been a WARN.
    def "FR2: a five-minute loop failing for an hour logs the first WARN and at most one roll-up per six runs"() {
        given:
        def fiveMinutes = Duration.ofMinutes(5)
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.clockedWait(fiveMinutes)) { n ->
            if (n <= 12) throw new IllegalStateException('tracker down')
            rig.stopHere()
        }

        when:
        rig.runToStop()

        then:
        def warns = rig.events(OperatorEvent.DAEMON_LOOP_TICK_FAILED)*.formattedMessage
        warns.size() == 2
        warns[0].contains('(1x so far)')
        warns[1].contains('(7x so far)')
        rig.atLevel(Level.WARN).size() == 2
        rig.atLevel(Level.DEBUG)*.formattedMessage.findAll {
            it.contains('still failing')
        }.size() == 10
    }

    // FR2 (daemon-supervision "Suppression runs on the loop's clock"): the roll-up and the
    //     recovery's outage duration are read from the clock the loop was built with — only the
    //     virtual clock moves, by the loop's own 1 min waits, and both edges appear.
    def "FR2: the roll-up and the recovery are timed on the loop's own virtual clock"() {
        given:
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.clockedWait(INTERVAL)) { n ->
            if (n <= 7) throw new IllegalStateException('tracker down')
            rig.stopHere()
        }

        when:
        rig.runToStop()

        then: 'six ticks of 1 min is the roll-up period, so the seventh failure is reminded at WARN'
        def warns = rig.events(OperatorEvent.DAEMON_LOOP_TICK_FAILED)*.formattedMessage
        warns.size() == 2
        warns[1].contains('(7x so far)')

        and: 'the recovery measures the outage on the virtual clock: seven 1 min waits'
        rig.atLevel(Level.INFO)*.formattedMessage == [
            'daemon loop recovered after 7 failure(s) over PT7M: last was tick failed: ' +
            'java.lang.IllegalStateException: tracker down'
        ]
    }
}
