package com.github.oinsio.gnomish.app.daemon

import static com.github.oinsio.gnomish.app.daemon.SupervisedLoopHarness.CAP
import static com.github.oinsio.gnomish.app.daemon.SupervisedLoopHarness.INTERVAL

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import java.time.Duration
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The second rung of supervision under the Unbounded policy (design D4, D5 of
 * supervise-daemon-loops-and-embed-dashboard): respawn after a doubling backoff, its reset, its
 * cap, and the stops that must prevent a respawn. The death is real — a failure whose own rendering
 * throws escapes the guard's report and ends the thread — and the backoff runs on a virtual
 * sleeper. The Bounded policy is {@code SupervisedLoopBoundedSpec}'s subject.
 */
@Timeout(10)
class SupervisedLoopRestartSpec extends Specification {

    private final SupervisedLoopHarness rig = new SupervisedLoopHarness(Level.INFO)
    private final VirtualSleeper backoffSleeper = new VirtualSleeper(new VirtualClock())

    def cleanup() {
        rig.close()
    }

    private List<Integer> restartCounts() {
        rig.events(OperatorEvent.DAEMON_LOOP_WORKER_DIED).collect {
            it.argumentArray[2] as Integer
        }
    }

    // FR3 (daemon-supervision "Unbounded policy respawns with growing backoff").
    def "an Unbounded loop respawns after a doubling backoff with a rising restart count"() {
        given:
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait(), { n ->
            if (n <= 3) throw new SupervisedLoopHarness.Unrenderable()
            rig.stopHere()
        }, new RestartPolicy.Unbounded(INTERVAL), backoffSleeper)

        when:
        rig.runToStop()

        then:
        backoffSleeper.slept == [
            INTERVAL,
            INTERVAL.multipliedBy(2),
            INTERVAL.multipliedBy(4)
        ]
        restartCounts() == [1, 2, 3]
        rig.loop.restartCount() == 3
        def died = rig.events(OperatorEvent.DAEMON_LOOP_WORKER_DIED)
        died.every {
            it.level == Level.ERROR && it.MDCPropertyMap['component'] == 'janitor'
        }
        died.every { it.throwableProxy.className == IllegalStateException.name }
    }

    // FR3 (daemon-supervision "A clean run resets the backoff").
    def "a clean tick resets the backoff while the restart count keeps rising"() {
        given:
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait(), { n ->
            if (n in [1, 2, 4]) throw new SupervisedLoopHarness.Unrenderable()
            if (n == 5) rig.stopHere()
        }, new RestartPolicy.Unbounded(INTERVAL), backoffSleeper)

        when:
        rig.runToStop()

        then:
        backoffSleeper.slept == [
            INTERVAL,
            INTERVAL.multipliedBy(2),
            INTERVAL
        ]
        restartCounts() == [1, 2, 3]
    }

    // FR3 (design D7): a cap below the base wins from the very first respawn.
    def "the cap wins from the first respawn when it is below the base"() {
        given:
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait(), { n ->
            if (n == 1) throw new SupervisedLoopHarness.Unrenderable()
            rig.stopHere()
        }, new RestartPolicy.Unbounded(Duration.ofHours(1)), backoffSleeper)

        when:
        rig.runToStop()

        then:
        backoffSleeper.slept == [CAP]
    }

    // FR4, NFR-R2: a death after a stop is neither counted, logged nor restarted.
    def "a worker dying after a stop is not restarted"() {
        given:
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait(), { n ->
            rig.stopHere()
            throw new SupervisedLoopHarness.Unrenderable()
        }, new RestartPolicy.Unbounded(INTERVAL), backoffSleeper)

        when:
        rig.runToStop()

        then:
        rig.ticks.get() == 1
        rig.loop.restartCount() == 0
        rig.atLevel(Level.ERROR).isEmpty()
        backoffSleeper.slept.isEmpty()
    }

    // FR4, NFR-R2 (daemon-supervision "Stop during a respawn backoff").
    def "a stop during the respawn backoff spawns no worker"() {
        given:
        def stoppingSleeper = { Duration d -> rig.stopHere() } as Sleeper
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait(), { n ->
            throw new SupervisedLoopHarness.Unrenderable()
        },
        new RestartPolicy.Unbounded(INTERVAL), stoppingSleeper)

        when:
        rig.runToStop()

        then:
        rig.ticks.get() == 1
        rig.events(OperatorEvent.DAEMON_LOOP_WORKER_DIED).size() == 1
    }

    // FR3: a backoff wait that throws is logged and the respawn goes ahead.
    def "a failing backoff wait is logged and the respawn proceeds"() {
        given:
        def failingSleeper = { Duration d ->
            throw new Error('backoff broke')
        } as Sleeper
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait(), { n ->
            if (n == 1) throw new SupervisedLoopHarness.Unrenderable()
            rig.stopHere()
        }, new RestartPolicy.Unbounded(INTERVAL), failingSleeper)

        when:
        rig.runToStop()

        then:
        rig.ticks.get() == 2
        def failed = rig.events(OperatorEvent.DAEMON_LOOP_BACKOFF_SLEEP_FAILED)
        failed.size() == 1
        failed[0].level == Level.WARN
        failed[0].throwableProxy.className == 'java.lang.Error'
    }
}
