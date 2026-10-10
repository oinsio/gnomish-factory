package com.github.oinsio.gnomish.app.daemon

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
 * The death handler's own lines (design D5 of supervise-daemon-loops-and-embed-dashboard): the
 * handler runs in the worker's uncaught-exception handler, where the JVM drops whatever it throws,
 * so a cause the log line cannot render must neither skip the respawn nor erase the line. Each
 * feature hands the handler a fault whose message throws — an {@link
 * SupervisedLoopHarness.UnrenderableTwice} killing the worker, a {@link
 * SupervisedLoopHarness.Wordless} failing the backoff wait — and asserts the supervision went on
 * and the line was still written, its cause replaced by a stand-in that names the fault's type.
 */
@Timeout(10)
class SupervisedLoopDeathLineSpec extends Specification {

    private final SupervisedLoopHarness rig = new SupervisedLoopHarness(Level.INFO)
    private final VirtualClock clock = new VirtualClock()
    private final VirtualSleeper backoffSleeper = new VirtualSleeper(clock)

    def cleanup() {
        rig.close()
    }

    // FR3, NFR-R2: the respawn does not depend on the death line rendering.
    def "a worker dying of a cause its line cannot render is still respawned, and the death still logged"() {
        given:
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait(), { n ->
            if (n == 1) throw new SupervisedLoopHarness.UnrenderableTwice()
            rig.stopHere()
        }, new RestartPolicy.Unbounded(INTERVAL), backoffSleeper)

        when:
        rig.runToStop()

        then:
        rig.ticks.get() == 2
        def died = rig.events(OperatorEvent.DAEMON_LOOP_WORKER_DIED)
        died.size() == 1
        died[0].level == Level.ERROR
        died[0].MDCPropertyMap['component'] == 'janitor'
        died[0].formattedMessage.contains('restart #1')
        died[0].throwableProxy.className == LoopEvents.UnrenderableCauseException.name
        died[0].throwableProxy.message.contains(SupervisedLoopHarness.UnrenderableTwice.name)
    }

    // FR3, FR9: the give-up is recorded, and logged, even when its cause cannot be rendered.
    def "a Bounded loop giving up on an unrenderable cause still logs the give-up"() {
        given:
        def policy = new RestartPolicy.Bounded(Duration.ofSeconds(1), 0, Duration.ofMinutes(10), clock)
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait(), { n ->
            throw new SupervisedLoopHarness.UnrenderableTwice()
        }, policy, backoffSleeper)

        when:
        rig.loop.start()
        rig.awaitEndWithinBound()

        then:
        rig.gaveUp == true
        def gaveUp = rig.events(OperatorEvent.DAEMON_LOOP_GAVE_UP)
        gaveUp.size() == 1
        gaveUp[0].level == Level.ERROR
        gaveUp[0].throwableProxy.className == LoopEvents.UnrenderableCauseException.name
        gaveUp[0].throwableProxy.message.contains(SupervisedLoopHarness.UnrenderableTwice.name)
    }

    // FR3: a backoff wait failing with an unrenderable fault is logged and the respawn goes ahead.
    def "a backoff wait failing with an unrenderable fault is still logged, and the respawn proceeds"() {
        given:
        def failingSleeper = { Duration d ->
            throw new SupervisedLoopHarness.Wordless()
        } as Sleeper
        rig.build(LoopOrder.TICK_THEN_WAIT, rig.fixedWait(), { n ->
            if (n == 1) throw new Error('tick died')
            rig.stopHere()
        }, new RestartPolicy.Unbounded(INTERVAL), failingSleeper)

        when:
        rig.runToStop()

        then:
        rig.ticks.get() == 2
        def failed = rig.events(OperatorEvent.DAEMON_LOOP_BACKOFF_SLEEP_FAILED)
        failed.size() == 1
        failed[0].level == Level.WARN
        failed[0].throwableProxy.className == LoopEvents.UnrenderableCauseException.name
        failed[0].throwableProxy.message.contains(SupervisedLoopHarness.Wordless.name)
    }
}
