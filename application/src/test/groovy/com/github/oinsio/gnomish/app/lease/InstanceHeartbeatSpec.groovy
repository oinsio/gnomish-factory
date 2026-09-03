package com.github.oinsio.gnomish.app.lease

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import spock.lang.Specification

/**
 * InstanceHeartbeat, the beat itself (design D1, D3): one tick beats every held claim with
 * the latest engine-event-derived payload and the injected alive-at instant — this instance's
 * own claims only. A beat failure never breaks the tick; a ClaimGone is surfaced and the
 * claim dropped. These specs drive tick() directly under a parked sleeper, so the beat
 * logic is exercised with no threading and no real time.
 *
 * FR1 of add-claim-heartbeat.
 */
class InstanceHeartbeatSpec extends Specification {

    private static final Duration INTERVAL = Duration.ofMinutes(5)
    private static final TaskRef A = new TaskRef('github:o/r#1')
    private static final TaskRef B = new TaskRef('github:o/r#2')

    private final Tracker tracker = Mock()
    private final HeartbeatProgress progress = new HeartbeatProgress()
    private final VirtualClock clock = new VirtualClock()
    private final List<TaskRef> lost = []
    // A parked sleeper: the auto-started worker blocks in its first sleep, so the only ticks
    // are the direct hb.tick() calls below — no background beating races these assertions.
    private final InstanceHeartbeat hb = new InstanceHeartbeat(
    tracker,
    progress,
    new BlockingSleeper(),
    clock,
    INTERVAL,
    { TaskRef ref -> lost << ref } as ClaimLostSink)

    private static final HeartbeatResult BEATEN = new HeartbeatResult.Beaten(new ClaimVersion('m', Instant.EPOCH, new ClaimEpoch(1)))

    private void progressAt(TaskRef ref, String stage, int attempt) {
        ProgressFixtures.progressAt(progress, ref, stage, attempt)
    }

    def cleanup() {
        // Drain the held set so any beat worker a register()-based feature started terminates on its
        // next pass. This bounds a sleep-dropping mutant's busy-spin (the worker checks the held set
        // each cycle) rather than leaking a spinning thread into PIT's reused minion.
        hb.unregister(A)
        hb.unregister(B)
    }

    // FR1: one tick beats every held claim exactly once, each with its own payload.
    def "one tick beats every held claim exactly once with its progress payload"() {
        given:
        progressAt(A, 'plan', 0)
        progressAt(B, 'review', 1)
        hb.register(A)
        hb.register(B)

        when:
        hb.tick()

        then:
        1 * tracker.heartbeat(A, 'stage=plan attempt=0 alive-at=1970-01-01T00:00:00Z') >> BEATEN
        1 * tracker.heartbeat(B, 'stage=review attempt=1 alive-at=1970-01-01T00:00:00Z') >> BEATEN
    }

    // FR1, D3: one instance-level thread serves every claim — a second claim does not spawn
    //     a second beat thread.
    def "a single instance-level thread serves all claims"() {
        when:
        hb.register(A)
        def worker = hb.worker()
        hb.register(B)

        then:
        worker != null
        hb.worker().is(worker)
    }

    // FR1: the payload reflects the LATEST engine event — a stage/attempt change between
    //     beats changes the next payload.
    def "the payload reflects the latest engine event"() {
        given:
        hb.register(A)

        when:
        progressAt(A, 'plan', 0)
        hb.tick()

        then:
        1 * tracker.heartbeat(A, 'stage=plan attempt=0 alive-at=1970-01-01T00:00:00Z') >> BEATEN

        when:
        progressAt(A, 'review', 2)
        hb.tick()

        then:
        1 * tracker.heartbeat(A, 'stage=review attempt=2 alive-at=1970-01-01T00:00:00Z') >> BEATEN
    }

    // FR1: alive-at comes from the injected clock and advances between beats.
    def "alive-at comes from the injected clock and advances"() {
        given:
        progressAt(A, 'plan', 0)
        hb.register(A)

        when:
        hb.tick()

        then:
        1 * tracker.heartbeat(A, 'stage=plan attempt=0 alive-at=1970-01-01T00:00:00Z') >> BEATEN

        when:
        clock.advance(Duration.ofMinutes(5))
        hb.tick()

        then:
        1 * tracker.heartbeat(A, 'stage=plan attempt=0 alive-at=1970-01-01T00:05:00Z') >> BEATEN
    }

    // FR1: unregister stops beating that claim; every other held claim is still beaten.
    def "unregister stops beating that claim while the rest keep being beaten"() {
        given:
        progressAt(A, 'plan', 0)
        progressAt(B, 'review', 1)
        hb.register(A)
        hb.register(B)

        when: 'both are held'
        hb.tick()

        then:
        1 * tracker.heartbeat(A, _) >> BEATEN
        1 * tracker.heartbeat(B, _) >> BEATEN

        when: 'A is unregistered'
        hb.unregister(A)
        hb.tick()

        then:
        0 * tracker.heartbeat(A, _)
        1 * tracker.heartbeat(B, _) >> BEATEN
    }

    // FR1, FR8: a beat that throws an infrastructure exception is caught, the loop continues
    //     to the next claim, and the failing claim is beaten again next tick (the thread survives).
    def "an infrastructure exception on a beat is caught and the loop continues"() {
        given:
        progressAt(A, 'plan', 0)
        progressAt(B, 'review', 1)
        hb.register(A)
        hb.register(B)

        when: 'A throws a 5xx-style exception; the tick must not propagate it'
        hb.tick()

        then: 'B is still beaten this tick'
        1 * tracker.heartbeat(A, _) >> { throw new RuntimeException('5xx') }
        1 * tracker.heartbeat(B, _) >> BEATEN
        noExceptionThrown()

        when: 'the next tick — the previously failing claim beats again'
        hb.tick()

        then:
        1 * tracker.heartbeat(A, _) >> BEATEN
        1 * tracker.heartbeat(B, _) >> BEATEN
    }

    // FR1, FR8: a ClaimGone result is surfaced through the sink and the claim dropped; the
    //     whole thread is unaffected and other claims keep being beaten.
    def "a ClaimGone result is surfaced through the sink and the claim dropped"() {
        given:
        progressAt(A, 'plan', 0)
        progressAt(B, 'review', 1)
        hb.register(A)
        hb.register(B)

        when:
        hb.tick()

        then:
        1 * tracker.heartbeat(A, _) >> new HeartbeatResult.ClaimGone()
        1 * tracker.heartbeat(B, _) >> BEATEN

        and: 'the lost claim was surfaced exactly once'
        lost == [A]

        when: 'the next tick — the dropped claim is no longer beaten, the other still is'
        hb.tick()

        then:
        0 * tracker.heartbeat(A, _)
        1 * tracker.heartbeat(B, _) >> BEATEN
    }

    // FR1, D3: the beat loop sleeps the interval before each tick and stops once no claim is held.
    //     Driven SYNCHRONOUSLY on the test thread (held seeded, worker never started) with a sleeper
    //     that records each interval — a first beat that loses the claim drains the held set so the
    //     loop terminates by data (mirroring the synchronous ExternalPolling loop spec). A mutant that
    //     drops the sleep leaves an empty record; a mutant that inverts the stop decision stops one
    //     tick too early; a mutant that drops the beat never drains and is caught by the safety bound —
    //     each fails deterministically here rather than hanging a background-thread test.
    def "the loop sleeps each interval and stops once the held set drains"() {
        given:
        def recorded = new CopyOnWriteArrayList<Duration>()
        Sleeper recordingSleeper = { Duration d ->
            recorded.add(d)
            if (recorded.size() > 20) {
                throw new IllegalStateException('loop did not terminate: the stop decision was inverted or the beat dropped')
            }
        } as Sleeper
        def gone = [
            heartbeat: { TaskRef ref, String payload ->
                new HeartbeatResult.ClaimGone()
            }
        ] as Tracker
        def loopHb = new InstanceHeartbeat(
                gone, progress, recordingSleeper, clock, INTERVAL, ClaimLostSink.IGNORE)
        progressAt(A, 'plan', 0)
        loopHb.seedHeldForTest(A)

        when: 'the loop runs to completion on this thread'
        loopHb.loop()

        then: 'it slept the interval before beating A, then once more before finding the set drained and stopping'
        recorded == [INTERVAL, INTERVAL]
    }

    // FR4 of harden-logging-observability: the loop's guard, driven synchronously on the test
    //     thread (the timing-sensitive rehearsal lives in InstanceHeartbeatLifecycleSpec, which PIT
    //     excludes). A collaborator bug costs one tick; the tick that works again closes the streak,
    //     so the operator's last word on a fault that ended is not the fault.
    def "a guarded tick announces the fault once and closes it when the tick works again"() {
        given: 'a sink whose confirmation callback throws until it is switched off'
        def throwing = new AtomicBoolean(true)
        ClaimLostSink toggleSink = new ClaimLostSink() {
                    void claimLost(TaskRef ref) {}
                    void claimConfirmed(TaskRef ref) {
                        if (throwing.get()) {
                            throw new IllegalStateException('sink boom')
                        }
                    }
                }
        def guarded = new InstanceHeartbeat(
                tracker, progress, new BlockingSleeper(), clock, INTERVAL, toggleSink)
        guarded.seedHeldForTest(A)
        progressAt(A, 'plan', 0)
        def logs = LogCaptureSupport.attach(HeartbeatTickLog)

        when: 'the first guarded tick blows up inside the sink and the second completes'
        guarded.tickGuarded()
        throwing.set(false)
        guarded.tickGuarded()

        then:
        2 * tracker.heartbeat(A, _) >> BEATEN

        and: 'the fault is announced exactly once, and never propagates out of the guard'
        noExceptionThrown()
        logs.list.count {
            it.formattedMessage.startsWith(OperatorEvent.HEARTBEAT_TICK_FAILED.head())
        } == 1

        and: 'the recovery closes it at INFO'
        logs.list.any { it.formattedMessage.contains('tick recovered') }

        cleanup:
        logs.detach()
    }
}
