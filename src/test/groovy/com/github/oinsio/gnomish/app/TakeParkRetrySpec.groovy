package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.app.take.TerminalWriteRetry
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory
import spock.lang.Specification

/**
 * FR10, D10, NFR-R3 of add-claim-heartbeat: the terminal PARK write of {@link TakeEscalationExit}
 * and {@link TakePauseExit} keeps the outcome durable in the branch and retries a tracker outage
 * with backoff, clearing the "tracker-write pending" marker ({@code onConfirmed}) only once the park
 * lands. A give-up past the bound leaves the marker set for reconcile-on-resume and still returns
 * the mapped {@code AwaitingHuman}. A virtual sleeper/clock makes the bound deterministic and
 * instant.
 */
class TakeParkRetrySpec extends Specification {

    static final TaskRef REF = new TaskRef('PROJ-1')
    static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    static final TaskState STATE = TaskState.atStageStart('build')
    static final TaskContext CONTEXT = new TaskContext('PROJ-1', 'title', 'body', List.<Decision> of())

    Tracker tracker = Mock()
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse('2026-01-01T00:00:00Z'))
    Clock clock = { -> now.get() } as Clock
    Sleeper sleeper = { Duration d -> now.set(now.get().plus(d)) } as Sleeper
    TerminalWriteRetry retry = new TerminalWriteRetry(sleeper, clock, Duration.ofMinutes(10))
    AtomicInteger confirmed = new AtomicInteger()

    private static TrackerTask taskWith(TrackerTaskState state) {
        new TrackerTask(REF, new TaskSnapshot(REF.id(), 'title', 'body'), state, AbortFacts.none())
    }

    /** Runs {@code emit} with a {@link ListAppender} attached to {@code exitClass}'s logger, returning the events. */
    private static List<ILoggingEvent> capture(Class<?> exitClass, Closure<Void> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(exitClass)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

    private static TaskOutcome.Escalated escalated() {
        new TaskOutcome.Escalated(STATE, new EscalationReport.AttemptsExhausted(3))
    }

    /**
     * A retry whose clock self-advances two minutes on each read, independently of the sleeper, so the
     * ~10-min bound is reached in a bounded number of polls even when a mutant drops {@code sleeper.sleep}
     * or halves the backoff — the give-up loop terminates (DEFERRED) instead of hanging. Mirrors the
     * self-ticking clock in {@code TerminalWriteRetrySpec}, where the killing tests for those mutants live.
     */
    private static TerminalWriteRetry givingUpRetry() {
        def ticking = new AtomicReference<Instant>(Instant.parse('2026-01-01T00:00:00Z'))
        Clock advancingClock = {
            ->
            def t = ticking.get()
            ticking.set(t.plus(Duration.ofMinutes(2)))
            t
        } as Clock
        Sleeper noop = { Duration d -> } as Sleeper
        new TerminalWriteRetry(noop, advancingClock, Duration.ofMinutes(10))
    }

    // FR10, D10: an escalation park that lands clears the pending marker exactly once.
    def "escalation park that lands runs the marker-clear callback once"() {
        given:
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))

        when:
        def result = TakeEscalationExit.exit(
                escalated(), tracker, REF, INSTANCE, retry, { confirmed.incrementAndGet() })

        then:
        1 * tracker.park(REF, ParkReason.ESCALATION, _ as String)
        confirmed.get() == 1
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.ESCALATION
    }

    // FR10, NFR-R3: a transient outage retries with backoff, then the confirmed park clears the marker.
    def "escalation park retries a transient outage then confirms and clears the marker"() {
        given: 'the park fails as unreachable twice, then lands'
        def attempts = new AtomicInteger()
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))
        tracker.park(REF, ParkReason.ESCALATION, _ as String) >> {
            if (attempts.getAndIncrement() < 2) {
                throw new TrackerUnavailableException('tracker down')
            }
        }

        when:
        def result = TakeEscalationExit.exit(
                escalated(), tracker, REF, INSTANCE, retry, { confirmed.incrementAndGet() })

        then:
        attempts.get() == 3
        confirmed.get() == 1
        result instanceof TakeResult.AwaitingHuman
    }

    // FR10, D10, NFR-R3: a tracker down past the bound gives up — the marker is left set (callback
    // never runs), the run still returns AwaitingHuman, and reconcile-on-resume will complete the
    // deferred park. The give-up SHALL emit exactly one ERROR naming the task and its unreconciled
    // ("tracker-write pending") state — the only trace an operator gets when the tracker was down
    // past the ~10-min bound, and the sole channel PIT does not mutate (avoidCallsTo slf4j).
    def "escalation park give-up leaves the marker set, logs the unreconciled state, and still returns AwaitingHuman"() {
        given:
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))
        tracker.park(REF, ParkReason.ESCALATION, _ as String) >> { throw new TrackerUnavailableException('still down') }
        def result = null

        when:
        def events = capture(TakeEscalationExit) {
            result = TakeEscalationExit.exit(
            escalated(), tracker, REF, INSTANCE, givingUpRetry(), { confirmed.incrementAndGet() })
        }

        then: 'the marker-clear callback never runs, so the branch keeps the pending marker'
        confirmed.get() == 0

        and: 'the run still returns the mapped AwaitingHuman (the branch carries the park)'
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.ESCALATION

        and: 'exactly one ERROR names the task and its unreconciled tracker-write state'
        def errors = events.findAll { it.level == Level.ERROR }
        errors.size() == 1
        errors[0].formattedMessage.contains('PROJ-1')
        errors[0].formattedMessage.contains('could not be written before the retry bound')
        errors[0].formattedMessage.contains('tracker-write pending')
    }

    // FR7 + FR10: a claim reaped/taken over mid-run skips the park AND the marker clear.
    def "escalation park skipped by the claim guard never clears the marker"() {
        given: 'the claim is no longer ours'
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working('other-instance'))

        when:
        def result = TakeEscalationExit.exit(
                escalated(), tracker, REF, INSTANCE, retry, { confirmed.incrementAndGet() })

        then:
        0 * tracker.park(*_)
        confirmed.get() == 0
        result instanceof TakeResult.AwaitingHuman
    }

    // FR10, D10: the checkpoint (Paused) park follows the same retry + marker-clear contract.
    def "checkpoint park that lands runs the marker-clear callback once"() {
        given:
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))
        def paused = new TaskOutcome.Paused(STATE, 'build')

        when:
        def result = TakePauseExit.finish(
                paused, CONTEXT, 'gnomish/PROJ-1', tracker, REF, INSTANCE, retry, { confirmed.incrementAndGet() })

        then:
        1 * tracker.park(REF, ParkReason.CHECKPOINT, _ as String)
        confirmed.get() == 1
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.CHECKPOINT
    }

    // FR10, D10, NFR-R3: the checkpoint park give-up leaves the marker set for reconcile too, and
    // likewise emits exactly one ERROR naming the task and its unreconciled ("tracker-write pending")
    // state — the operator's only trace of a checkpoint that never reached the tracker.
    def "checkpoint park give-up leaves the marker set, logs the unreconciled state, and still returns AwaitingHuman"() {
        given:
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))
        tracker.park(REF, ParkReason.CHECKPOINT, _ as String) >> { throw new TrackerUnavailableException('down') }
        def paused = new TaskOutcome.Paused(STATE, 'build')
        def result = null

        when:
        def events = capture(TakePauseExit) {
            result = TakePauseExit.finish(
            paused, CONTEXT, 'gnomish/PROJ-1', tracker, REF, INSTANCE, givingUpRetry(), { confirmed.incrementAndGet() })
        }

        then:
        confirmed.get() == 0
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.CHECKPOINT

        and: 'exactly one ERROR names the task and its unreconciled tracker-write state'
        def errors = events.findAll { it.level == Level.ERROR }
        errors.size() == 1
        errors[0].formattedMessage.contains('PROJ-1')
        errors[0].formattedMessage.contains('could not be written before the retry bound')
        errors[0].formattedMessage.contains('tracker-write pending')
    }
}
