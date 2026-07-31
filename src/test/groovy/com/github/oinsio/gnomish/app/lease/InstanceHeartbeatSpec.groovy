package com.github.oinsio.gnomish.app.lease

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * InstanceHeartbeat, the beat itself (design D1, D3): one tick beats every held claim with
 * the latest engine-event-derived payload and the injected alive-at instant, then runs the
 * reaper seam once. A beat failure never breaks the tick; a ClaimGone is surfaced and the
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
    private final AtomicInteger reaps = new AtomicInteger()
    // A parked sleeper: the auto-started worker blocks in its first sleep, so the only ticks
    // are the direct hb.tick() calls below — no background beating races these assertions.
    private final InstanceHeartbeat hb = new InstanceHeartbeat(
    tracker,
    progress,
    new BlockingSleeper(),
    clock,
    INTERVAL,
    { TaskRef ref -> lost << ref } as ClaimLostSink,
    { reaps.incrementAndGet() } as ReaperDuty)

    private static final HeartbeatResult BEATEN = new HeartbeatResult.Beaten(new ClaimVersion('m', Instant.EPOCH))

    private void progressAt(TaskRef ref, String stage, int attempt) {
        progress.onEvent(new EngineEvent.AttemptStarted(new AttemptKey(ref.id(), stage, attempt)))
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
            heartbeat: { TaskRef ref, String payload -> new HeartbeatResult.ClaimGone() }
        ] as Tracker
        def loopHb = new InstanceHeartbeat(
                gone, progress, recordingSleeper, clock, INTERVAL, ClaimLostSink.IGNORE, ReaperDuty.NONE)
        progressAt(A, 'plan', 0)
        loopHb.seedHeldForTest(A)

        when: 'the loop runs to completion on this thread'
        loopHb.loop()

        then: 'it slept the interval before beating A, then once more before finding the set drained and stopping'
        recorded == [INTERVAL, INTERVAL]
    }

    // FR1, FR4, D4: a per-tick failure (here the reaper throwing) is caught and logged WARN, the loop
    //     survives it and runs to completion. Driven synchronously: a first beat that loses the claim
    //     drains the held set so the loop terminates, while the reaper throws every tick so the catch
    //     and its WARN line are exercised — a mutant that drops the catch propagates the throw out of
    //     loop() (failing the run), and one that drops the WARN leaves no log event.
    def "the loop catches and logs a per-tick failure and still runs to completion"() {
        given:
        def gone = [
            heartbeat: { TaskRef ref, String payload -> new HeartbeatResult.ClaimGone() }
        ] as Tracker
        def boom = { throw new IllegalStateException('reaper down') } as ReaperDuty
        int cycles = 0
        Sleeper safety = { Duration d ->
            if (++cycles > 20) {
                throw new IllegalStateException('loop did not terminate')
            }
        } as Sleeper
        def loopHb = new InstanceHeartbeat(gone, progress, safety, clock, INTERVAL, ClaimLostSink.IGNORE, boom)
        progressAt(A, 'plan', 0)
        loopHb.seedHeldForTest(A)

        Logger logbackLogger = (Logger) LoggerFactory.getLogger(InstanceHeartbeat)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)

        when: 'the loop runs to completion despite the reaper throwing each tick'
        try {
            loopHb.loop()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }

        then: 'the loop terminated (no exception escaped) and logged the tick failure as a WARN'
        appender.list.any { it.level == Level.WARN && it.formattedMessage.contains('heartbeat tick failed') }
    }

    // FR4: each tick runs the reaper duty exactly once, after beating (the task-4.3 seam).
    def "each tick runs the reaper duty once after beating"() {
        given:
        progressAt(A, 'plan', 0)
        hb.register(A)

        when:
        hb.tick()
        hb.tick()

        then:
        2 * tracker.heartbeat(A, _) >> BEATEN
        reaps.get() == 2
    }

    // FR4, D13: the tick hands the reaper exactly the claims the instance holds, so the reaper
    //     can exclude its own live claims from staleness observation and never reap itself.
    //     Seeded via seedHeldForTest so no worker thread starts (the parked-sleeper races nothing).
    def "each tick passes the held claims to the reaper so its own claims are never reaped"() {
        given:
        ReaperDuty capturingReaper = Mock()
        def hb2 = new InstanceHeartbeat(
                tracker, progress, new BlockingSleeper(), clock, INTERVAL, ClaimLostSink.IGNORE, capturingReaper)
        progressAt(A, 'plan', 0)
        progressAt(B, 'review', 1)
        hb2.seedHeldForTest(A)
        hb2.seedHeldForTest(B)

        when:
        hb2.tick()

        then:
        1 * tracker.heartbeat(A, _) >> BEATEN
        1 * tracker.heartbeat(B, _) >> BEATEN
        1 * capturingReaper.reapOnce({ (it as Set) == ([A, B] as Set) })
    }
}
