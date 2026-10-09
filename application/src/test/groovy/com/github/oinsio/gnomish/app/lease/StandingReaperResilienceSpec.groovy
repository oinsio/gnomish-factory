package com.github.oinsio.gnomish.app.lease

import static com.github.oinsio.gnomish.app.lease.ReaperLoopRig.Unrenderable

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import java.time.Duration
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The standing reaper's thread survives abnormal faults (FR3, FR4 of fix-reaper-idle-liveness;
 * NFR-R2 of that change reduces to them), now as a supervised daemon loop (FR6 of
 * supervise-daemon-loops-and-embed-dashboard, design D6, D7): the loop waits the reaper's interval
 * and then ticks, an {@code Error} from the duty or a throwing sleeper is logged as the loop's
 * {@code DAEMON_LOOP_TICK_FAILED} and never ends it, and a thread that dies anyway is respawned.
 * Every line names the loop by {@code component=reaper}, the key that replaced the reaper's own
 * codes (GF067–GF069, retired).
 *
 * <p>The death is real: a failure whose own rendering throws escapes the loop's guard and ends
 * the thread. The loop's general behavior is {@code SupervisedLoop*Spec}'s subject; this spec pins
 * the reaper's wiring of it — order, interval, policy, component and codes.
 */
@Timeout(10)
class StandingReaperResilienceSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final String WAIT = "sleep ${INTERVAL}".toString()

    private final ReaperLoopRig rig = new ReaperLoopRig()

    def cleanup() {
        rig.close()
    }

    // FR6: the reaper waits its interval before its first tick and between ticks (wait → tick).
    def "the reaper waits its interval before every tick"() {
        given:
        rig.build(INTERVAL, { n ->
            if (n == 2) rig.stopHere()
        })

        when:
        rig.runToStop()

        then:
        rig.journal == [WAIT, 'tick1', WAIT, 'tick2']
        rig.atOrAbove(Level.WARN).empty
    }

    // FR3 of fix-reaper-idle-liveness, FR6: an Error from the duty is the loop's tick failure,
    //     logged at WARN with component=reaper, and the next tick still reaps.
    def "an Error from the duty is logged with the loop's code and component, and the next tick still reaps"() {
        given:
        rig.build(INTERVAL, { n ->
            if (n == 1) throw new AssertionError('duty exploded on the first tick' as Object)
            rig.stopHere()
        })

        when:
        rig.runToStop()

        then:
        rig.journal == [WAIT, 'tick1', WAIT, 'tick2']
        def failed = rig.events(OperatorEvent.DAEMON_LOOP_TICK_FAILED)
        failed.size() == 1
        failed[0].level == Level.WARN
        failed[0].MDCPropertyMap['component'] == 'reaper'
        failed[0].throwableProxy.className == AssertionError.name
    }

    // FR3 of fix-reaper-idle-liveness, FR6: a throwing sleeper is the loop's failure too — logged
    //     with component=reaper — and the loop still reaches its tick.
    def "a throwing sleeper is logged with the loop's code and component, and the loop still ticks"() {
        given:
        rig.build(INTERVAL, { n -> rig.stopHere() }, { n, d ->
            if (n == 1) throw new IllegalStateException('sleeper blew up')
        })

        when:
        rig.runToStop()

        then:
        rig.journal == [WAIT, 'tick1']
        def failed = rig.events(OperatorEvent.DAEMON_LOOP_TICK_FAILED)
        failed.size() == 1
        failed[0].level == Level.WARN
        failed[0].MDCPropertyMap['component'] == 'reaper'
    }

    // FR4 of fix-reaper-idle-liveness, FR6: a dead thread is respawned after a backoff of the
    //     reaper's interval, logging the loop's ERROR with component=reaper and restart #1.
    def "a dead reaper thread is respawned after an interval's backoff"() {
        given:
        rig.build(INTERVAL, { n ->
            if (n == 1) throw new Unrenderable()
            rig.stopHere()
        })

        when:
        rig.runToStop()

        then: 'wait, death, backoff of one interval, then the fresh thread waits and ticks'
        rig.journal == [
            WAIT,
            'tick1',
            WAIT,
            WAIT,
            'tick2'
        ]
        def died = rig.events(OperatorEvent.DAEMON_LOOP_WORKER_DIED)
        died.size() == 1
        died[0].level == Level.ERROR
        died[0].MDCPropertyMap['component'] == 'reaper'
        died[0].argumentArray[0] == 'gnomish-reaper'
        died[0].argumentArray[1] == INTERVAL
        died[0].argumentArray[2] == 1
        rig.reaper.restartCount() == 1
    }

    // FR6: the backoff wait before a respawn is guarded; its failure is the loop's
    //     DAEMON_LOOP_BACKOFF_SLEEP_FAILED with component=reaper, and the respawn still happens.
    def "a backoff sleep that throws is logged with the loop's code and component, and the respawn still happens"() {
        given: 'the second sleep is the backoff after the first tick died'
        rig.build(INTERVAL, { n ->
            if (n == 1) throw new Unrenderable()
            rig.stopHere()
        }, { n, d ->
            if (n == 2) throw new IllegalStateException('backoff sleep blew up')
        })

        when:
        rig.runToStop()

        then:
        rig.ticks.get() == 2
        def failed = rig.events(OperatorEvent.DAEMON_LOOP_BACKOFF_SLEEP_FAILED)
        failed.size() == 1
        failed[0].level == Level.WARN
        failed[0].MDCPropertyMap['component'] == 'reaper'
    }
}
