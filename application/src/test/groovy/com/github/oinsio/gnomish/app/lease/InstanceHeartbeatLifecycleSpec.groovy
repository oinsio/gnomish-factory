package com.github.oinsio.gnomish.app.lease

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.logtext.ShutdownPhase
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification
import spock.lang.Timeout

/**
 * InstanceHeartbeat, the thread lifecycle (design D3): the beat thread auto-starts with the
 * FIRST claim and stops itself once no claim is held. The virtual {@link BlockingSleeper}
 * makes the loop deterministic — the spec drives it one tick at a time and joins the worker
 * to observe a stop, so there is no real sleeping and no polling.
 *
 * FR1 of add-claim-heartbeat.
 */
@Timeout(10)
class InstanceHeartbeatLifecycleSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final TaskRef REF = new TaskRef('github:o/r#1')

    private final AtomicInteger beats = new AtomicInteger()
    private final HeartbeatProgress progress = new HeartbeatProgress()
    private final BlockingSleeper sleeper = new BlockingSleeper()
    private final Tracker tracker = [
        heartbeat: { TaskRef ref, String payload ->
            beats.incrementAndGet()
            new HeartbeatResult.Beaten(new ClaimVersion('m', Instant.EPOCH, new ClaimEpoch(1)))
        }
    ] as Tracker
    private final InstanceHeartbeat hb = new InstanceHeartbeat(
    tracker, progress, sleeper, new VirtualClock(), INTERVAL, ClaimLostSink.IGNORE)
    // Every heartbeat a test starts a real worker on, drained in cleanup (see cleanup()).
    private final List<InstanceHeartbeat> started = [hb]

    def setup() {
        ShutdownPhase.reset()
        progress.onEvent(new EngineEvent.AttemptStarted(new AttemptKey(REF.id(), 'plan', 0)))
    }

    def cleanup() {
        // Drain every started worker's held set so it terminates on its next pass. This bounds a
        // sleep-dropping mutant's busy-spin (the worker checks the held set each cycle) rather than
        // leaking a spinning thread into PIT's reused minion — see InstanceHeartbeatSpec.
        started.each { it.unregister(REF) }
    }

    /**
     * A sleeper that delegates to {@code base} except on its second call, where it throws
     * {@code death} — used by both abnormal-death specs below to avoid duplicating the
     * throw-on-second-call wiring twice in this file.
     */
    private static Sleeper diesOnSecondSleep(BlockingSleeper base, Throwable death) {
        def calls = new AtomicInteger()
        Sleeper sleeper = { Duration d ->
            if (calls.incrementAndGet() == 2) {
                throw death
            }
            base.sleep(d)
        } as Sleeper
        return sleeper
    }

    // FR1, D3: the beat starts with the first claim — the thread starts, sleeps the interval,
    //     and beats within one interval.
    def "the beat thread starts with the first claim and beats on the interval"() {
        when: 'the first claim registers'
        hb.register(REF)
        def slept = sleeper.awaitEntered()

        then: 'the worker sleeps the configured interval and has not beaten yet'
        slept == INTERVAL
        beats.get() == 0

        when: 'one interval elapses'
        sleeper.releaseOne()
        sleeper.awaitEntered()

        then: 'exactly one beat landed'
        beats.get() == 1
    }

    // FR1: N intervals produce N beats.
    def "N intervals produce N beats"() {
        given:
        hb.register(REF)
        sleeper.awaitEntered()

        when:
        3.times {
            sleeper.releaseOne()
            sleeper.awaitEntered()
        }

        then:
        beats.get() == 3
    }

    // FR1, D3: the thread stops when no claim is held — after the only claim is unregistered
    //     the loop finds the held set empty and terminates, having beaten nothing.
    def "the thread stops when no claim is held"() {
        given:
        hb.register(REF)
        sleeper.awaitEntered()

        when: 'the only claim is dropped and the sleep returns'
        hb.unregister(REF)
        sleeper.releaseOne()
        hb.worker().join()

        then: 'the worker terminated and never beat'
        !hb.worker().isAlive()
        beats.get() == 0
    }

    // FR1, D3: a claim registered after a stop restarts the thread — proving the stop truly
    //     ended the thread and the running flag was reset.
    def "a new claim after a stop restarts beating"() {
        given: 'a first claim that is dropped so the thread stops'
        hb.register(REF)
        sleeper.awaitEntered()
        hb.unregister(REF)
        sleeper.releaseOne()
        hb.worker().join()

        when: 'a new claim registers and one interval elapses'
        hb.register(REF)
        sleeper.awaitEntered()
        sleeper.releaseOne()
        sleeper.awaitEntered()

        then: 'a fresh worker resumed beating'
        beats.get() == 1
    }

    // FR1, D3: the abnormal death path is not silent. When the worker thread dies on an
    //     uncaught Error (here a sleeper that throws on its second call, mimicking an
    //     OOM/StackOverflow surfacing from deep in an adapter) the uncaught-exception handler
    //     logs it at ERROR — so the operator sees the CAUSE, not only the later stale-and-reaped
    //     effect — and clears `running`, so a subsequent claim of the same run starts a FRESH,
    //     live worker instead of assuming the dead one is still beating. The design deliberately
    //     does NOT resurrect the thread to keep beating the in-flight claim (degradation is the
    //     death path); this only makes the death loud and restart-safe.
    def "an abnormal worker death is logged at ERROR and clears running so a new claim restarts it"() {
        given: 'a sleeper that throws an Error on its second call, otherwise the rendezvous sleeper'
        def base = new BlockingSleeper()
        def sleeper = diesOnSecondSleep(base, new StackOverflowError('adapter blew the stack'))
        def dying = new InstanceHeartbeat(
                tracker, progress, sleeper, new VirtualClock(), INTERVAL, ClaimLostSink.IGNORE)
        started << dying

        def logs = LogCaptureSupport.attach(InstanceHeartbeat)

        when: 'the claim registers and the worker beats once, then dies on the second sleep'
        dying.register(REF)
        def deadWorker = dying.worker()
        base.awaitEntered()
        base.releaseOne()
        deadWorker.join()

        then: 'the worker was the named heartbeat thread, its one beat landed, and the death was logged loudly (ERROR) naming the thread'
        deadWorker.name == 'gnomish-heartbeat'
        beats.get() == 1
        logs.list.any {
            it.level == Level.ERROR &&
            it.formattedMessage.contains('heartbeat thread gnomish-heartbeat died')
        }

        when: 'a later claim of the same run registers'
        dying.register(REF)
        def restarted = dying.worker()
        base.awaitEntered()

        then: 'running had been cleared, so a distinct, live worker took over'
        !restarted.is(deadWorker)
        restarted.isAlive()

        cleanup: 'let the restarted worker find the set drained and stop'
        logs.detach()
        dying.unregister(REF)
        base.releaseOne()
        restarted.join()
    }

    // FR2, D3: liveClaimsSnapshot() reflects the claims a running worker is actively beating —
    //     a StandingReaper reads this to exclude actively-beaten claims from staleness checks.
    def "liveClaimsSnapshot returns the held claims while the worker is running"() {
        when: 'the claim registers and the worker is running'
        hb.register(REF)
        sleeper.awaitEntered()

        then: 'the snapshot reports the held claim'
        hb.liveClaimsSnapshot() == [REF] as Set
    }

    // FR2, D3: a dead heartbeat (worker died abnormally, running cleared) must stop shielding
    //     its claims — liveClaimsSnapshot() returns empty once running == false, even though the
    //     claim is still registered/held, so a StandingReaper can treat it as stale again.
    def "liveClaimsSnapshot is empty after the worker dies abnormally"() {
        given: 'a sleeper that throws an Error on its second call, otherwise the rendezvous sleeper'
        def base = new BlockingSleeper()
        def sleeper = diesOnSecondSleep(base, new StackOverflowError('adapter blew the stack'))
        def dying = new InstanceHeartbeat(
                tracker, progress, sleeper, new VirtualClock(), INTERVAL, ClaimLostSink.IGNORE)
        started << dying

        when: 'the claim registers and the worker beats once, then dies on the second sleep'
        dying.register(REF)
        def deadWorker = dying.worker()
        base.awaitEntered()
        base.releaseOne()
        deadWorker.join()

        then: 'the worker is dead and the snapshot is empty despite the claim still being held'
        !deadWorker.isAlive()
        dying.liveClaimsSnapshot().isEmpty()
    }

    // FR15 of harden-logging-observability: the loop's own guard around tick(). A sink that throws
    // is the reachable way in — the per-beat failures are already swallowed one level down — and it
    // is exactly the shape the guard exists for: a collaborator bug must cost one tick, not the
    // heartbeat thread. A lost tick that says nothing is a claim quietly going stale, so it is WARN.
    def "a tick that throws is logged at WARN and the thread beats again on the next interval"() {
        given: 'a claim-lost sink whose confirmation callback throws on every beat'
        ClaimLostSink throwingSink = new ClaimLostSink() {
                    void claimLost(TaskRef ref) {}
                    void claimConfirmed(TaskRef ref) {
                        throw new IllegalStateException('sink boom')
                    }
                }
        def sleeper = new BlockingSleeper()
        def guarded = new InstanceHeartbeat(
                tracker, progress, sleeper, new VirtualClock(), INTERVAL, throwingSink)
        started << guarded
        // The tick's edge logging moved to HeartbeatTickLog with FR4's repeat suppression, so the
        // line is attributed to that class now; the level and the code are unchanged.
        def logs = LogCaptureSupport.attach(HeartbeatTickLog)

        when: 'the claim registers, the first tick blows up, and the loop reaches its next sleep'
        guarded.register(REF)
        sleeper.awaitEntered()
        sleeper.releaseOne()
        sleeper.awaitEntered()

        then: 'the thread survived the failing tick'
        guarded.worker().isAlive()

        and:
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.HEARTBEAT_TICK_FAILED.head())
        }
        event != null
        event.level == Level.WARN

        cleanup:
        logs.detach()
    }
}
