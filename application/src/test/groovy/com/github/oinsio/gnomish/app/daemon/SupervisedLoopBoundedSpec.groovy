package com.github.oinsio.gnomish.app.daemon

import static com.github.oinsio.gnomish.app.daemon.SupervisedLoopHarness.CAP
import static com.github.oinsio.gnomish.app.daemon.SupervisedLoopHarness.INTERVAL

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The Bounded restart policy (design D5 of supervise-daemon-loops-and-embed-dashboard): respawns
 * like the Unbounded one until more than its maximum restarts fall within its sliding window, then
 * gives up with one ERROR and no respawn. Virtual time for the window clock and the backoff.
 */
@Timeout(10)
class SupervisedLoopBoundedSpec extends Specification {

    private final SupervisedLoopHarness rig = new SupervisedLoopHarness(Level.INFO)
    private final VirtualClock clock = new VirtualClock()
    private final VirtualSleeper backoffSleeper = new VirtualSleeper(clock)

    def cleanup() {
        rig.close()
    }

    private List<Integer> restartCounts() {
        rig.events(OperatorEvent.DAEMON_LOOP_WORKER_DIED).collect {
            it.argumentArray[2] as Integer
        }
    }

    // FR3 (daemon-supervision "Bounded policy gives up").
    def "a Bounded loop gives up on the sixth death within its window and respawns no more"() {
        given:
        def policy = new RestartPolicy.Bounded(Duration.ofSeconds(1), CAP, 5, Duration.ofMinutes(10), clock)
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait(), { n ->
            throw new SupervisedLoopHarness.Unrenderable()
        },
        policy, backoffSleeper)

        when:
        rig.loop.start()
        rig.joinWithinBound()

        then:
        rig.ticks.get() == 6
        restartCounts() == [1, 2, 3, 4, 5]
        backoffSleeper.slept == [1, 2, 4, 8, 16].collect {
            Duration.ofSeconds(it)
        }
        rig.loop.restartCount() == 5
        def gaveUp = rig.events(OperatorEvent.DAEMON_LOOP_GAVE_UP)
        gaveUp.size() == 1
        gaveUp[0].level == Level.ERROR
        gaveUp[0].argumentArray[1..3] == [5, 5, Duration.ofMinutes(10)]
    }

    // FR3: the Bounded policy resets its backoff on a clean tick exactly as the Unbounded one does.
    def "a clean tick resets a Bounded loop's backoff too"() {
        given:
        def policy = new RestartPolicy.Bounded(Duration.ofSeconds(1), CAP, 5, Duration.ofMinutes(10), clock)
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait(), { n ->
            if (n in [1, 3]) throw new SupervisedLoopHarness.Unrenderable()
            if (n == 4) rig.stopHere()
        }, policy, backoffSleeper)

        when:
        rig.runToStop()

        then:
        backoffSleeper.slept == [
            Duration.ofSeconds(1),
            Duration.ofSeconds(1)
        ]
        restartCounts() == [1, 2]
    }

    // FR3: the window slides — restarts older than it no longer count toward the bound.
    def "a Bounded loop whose deaths are spread wider than its window keeps respawning"() {
        given:
        def policy = new RestartPolicy.Bounded(Duration.ofSeconds(1), CAP, 5, Duration.ofMinutes(10), clock)
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait(), { n ->
            clock.advance(Duration.ofMinutes(10))
            if (n <= 7) throw new SupervisedLoopHarness.Unrenderable()
            rig.stopHere()
        }, policy, backoffSleeper)

        when:
        rig.runToStop()

        then:
        rig.loop.restartCount() == 7
        rig.events(OperatorEvent.DAEMON_LOOP_GAVE_UP).isEmpty()
    }

    // FR4, NFR-R2: a stop landing after the decision but before the backoff starts skips both the
    //     backoff and the respawn. The window clock is read under the decision, so it stops there.
    def "a stop arriving while the restart is decided skips the backoff and the respawn"() {
        given:
        def stopOnRead = { -> rig.stopHere(); Instant.EPOCH } as Clock
        def policy = new RestartPolicy.Bounded(INTERVAL, CAP, 5, Duration.ofMinutes(10), stopOnRead)
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait(), { n ->
            throw new SupervisedLoopHarness.Unrenderable()
        },
        policy, backoffSleeper)

        when:
        rig.runToStop()

        then:
        rig.ticks.get() == 1
        backoffSleeper.slept.isEmpty()
    }
}
