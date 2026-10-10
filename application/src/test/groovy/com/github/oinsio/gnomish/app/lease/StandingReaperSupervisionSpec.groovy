package com.github.oinsio.gnomish.app.lease


import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import java.time.Duration
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The standing reaper's restart policy (FR4, NFR-O1, UX2 of fix-reaper-idle-liveness, design D5),
 * now the supervised loop's {@code Unbounded} policy with the reaper's interval as the first
 * backoff and a 10-minute cap (FR6 of supervise-daemon-loops-and-embed-dashboard, design D7):
 * consecutive deaths double the backoff up to the cap, a clean tick resets it, every respawn logs
 * the loop's ERROR with a rising restart count and {@code component=reaper}, and the reaper's
 * {@code restartCount()} — the {@code vitals.reaper.restartCount} source — reads that same count.
 *
 * <p>Deaths are real (an {@code Error} from the duty leaves the loop's guard); the backoffs are read off the
 * respawn lines, and the first feature also checks them against what the reaper's one sleeper
 * actually slept.
 */
@Timeout(10)
class StandingReaperSupervisionSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(1)
    private static final Duration CAP = Duration.ofMinutes(10)

    private final ReaperLoopRig rig = new ReaperLoopRig()

    def cleanup() {
        rig.close()
    }

    // The backoff each respawn waited, as its ERROR line reports it.
    private List<Duration> backoffs() {
        rig.events(OperatorEvent.DAEMON_LOOP_WORKER_DIED).collect {
            it.argumentArray[1] as Duration
        }
    }

    private List<Integer> restartCounts() {
        rig.events(OperatorEvent.DAEMON_LOOP_WORKER_DIED).collect {
            it.argumentArray[2] as Integer
        }
    }

    // FR4, D5 of fix-reaper-idle-liveness; FR6: consecutive deaths double the backoff from the
    //     reaper's interval, and the 10-minute cap stops further growth.
    def "consecutive deaths double the backoff from the interval up to the 10-minute cap"() {
        given:
        rig.build(INTERVAL, { n ->
            if (n <= 6) throw new Error('duty died')
            rig.stopHere()
        })

        when:
        rig.runToStop()

        then: 'every death is a wait, its tick and a backoff, which the respawn line reports'
        def slept = rig.journal.findAll {
            it.startsWith('sleep')
        }.collect {
            Duration.parse(it - 'sleep ')
        }
        slept.indices.findAll {
            it % 2 == 1
        }.collect {
            slept[it]
        } == backoffs()
        backoffs() == [
            INTERVAL,
            INTERVAL.multipliedBy(2),
            INTERVAL.multipliedBy(4),
            INTERVAL.multipliedBy(8),
            CAP,
            CAP
        ]
    }

    // FR4, D5 of fix-reaper-idle-liveness; FR6: a clean tick after a respawn resets the backoff,
    //     so a later death starts again from the interval.
    def "a clean tick after a respawn resets the backoff to the interval"() {
        given:
        rig.build(INTERVAL, { n ->
            if (n in [1, 2, 4]) throw new Error('duty died')
            if (n == 5) rig.stopHere()
        })

        when:
        rig.runToStop()

        then:
        backoffs() == [
            INTERVAL,
            INTERVAL.multipliedBy(2),
            INTERVAL
        ]
        restartCounts() == [1, 2, 3]
    }

    // FR4, NFR-O1, UX2 of fix-reaper-idle-liveness; FR6: each respawn logs the loop's ERROR with
    //     component=reaper and a rising count, and restartCount() reports the same lifetime count.
    def "each respawn logs an ERROR with a rising restart count that restartCount() reports"() {
        given:
        rig.build(INTERVAL, { n ->
            if (n <= 3) throw new Error('duty died')
            rig.stopHere()
        })

        expect: 'a reaper that never died has restarted zero times'
        rig.reaper.restartCount() == 0

        when:
        rig.runToStop()

        then:
        restartCounts() == [1, 2, 3]
        rig.events(OperatorEvent.DAEMON_LOOP_WORKER_DIED).every {
            it.level == Level.ERROR && it.MDCPropertyMap['component'] == 'reaper'
        }
        rig.reaper.restartCount() == 3
    }
}
