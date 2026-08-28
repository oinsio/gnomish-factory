package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException
import com.github.oinsio.gnomish.app.take.FinishTransition
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.app.take.TerminalWriteRetry
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
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
 * FR18, D11 of add-tracker-port (task 5.11): a fresh {@code Completed} outcome must render the
 * real final report (task/stage/attempts/usage via {@link com.github.oinsio.gnomish.status.StatusReport}
 * and {@link com.github.oinsio.gnomish.status.StatusTextRenderer}, plus the task branch name) and
 * actually call {@code tracker.finish} — {@link TakeEngineExecution} previously fell through to
 * {@code TakeOutcomeMapper#map}'s placeholder ("Task completed.") without ever calling the tracker.
 */
class TakeFinishReportSpec extends Specification {

    static final TaskRef REF = new TaskRef('PROJ-1')
    static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    static final TaskContext CONTEXT = new TaskContext('PROJ-1', 'Fix the widget', 'body', List.<Decision> of())
    static final TaskState STATE = new TaskState(new Position.PipelineEnd(), 0, [], ExecutorUsage.none())
    static final String BRANCH = 'gnomish/PROJ-1'

    Tracker tracker = Mock()

    private static TrackerTask taskWith(TrackerTaskState state) {
        new TrackerTask(REF, new TaskSnapshot(REF.id(), 'title', 'body'), state, AbortFacts.none(), false)
    }

    private static List<ILoggingEvent> capture(Closure<?> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(TakeFinishReport)
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

    // FR18, D11: finish is called with a non-blank summary rendered from StatusReport.
    def "finish renders a full report and calls tracker.finish"() {
        given: 'the claim is still ours, so the pre-write guard lets the finish through (FR7)'
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))
        def completed = new TaskOutcome.Completed(STATE)

        when:
        def result = TakeFinishReport.finish(completed, CONTEXT, BRANCH, tracker, REF, INSTANCE)

        then:
        1 * tracker.finish(REF, { String summary ->
            summary.contains('PROJ-1') &&
            summary.contains('Fix the widget') &&
            summary.contains('pipeline complete') &&
            summary.contains(BRANCH)
        })

        and:
        result instanceof TakeResult.Delivered
        def delivered = result as TakeResult.Delivered
        delivered.finalState() == STATE
        delivered.summary().contains(BRANCH)
    }

    // FR18, D11: the returned TakeResult carries exactly the summary text passed to tracker.finish.
    def "finish returns a Delivered result whose summary matches the tracker.finish call"() {
        given:
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))
        def completed = new TaskOutcome.Completed(STATE)
        String captured = null

        when:
        def result = TakeFinishReport.finish(completed, CONTEXT, BRANCH, tracker, REF, INSTANCE)

        then:
        1 * tracker.finish(REF, _ as String) >> { TaskRef ref, String summary ->
            captured = summary
        }

        and:
        (result as TakeResult.Delivered).summary() == captured
    }

    // FR7 of add-claim-heartbeat: the finish write is git-unfenced, so a claim reaped/taken over
    // mid-run must NOT overwrite the new holder's state — the pre-write guard skips the finish.
    def "finish skips the tracker write when the claim is no longer ours (#state)"() {
        given: 'the pre-write check sees the claim is not held by this instance'
        tracker.fetchTask(REF) >> taskWith(state)
        def completed = new TaskOutcome.Completed(STATE)

        when:
        def result = TakeFinishReport.finish(completed, CONTEXT, BRANCH, tracker, REF, INSTANCE)

        then: 'no finish is written'
        0 * tracker.finish(*_)

        and: 'the run still returns the mapped Delivered result (the branch carries the outcome)'
        result instanceof TakeResult.Delivered
        (result as TakeResult.Delivered).summary().contains(BRANCH)

        where:
        state << [
            new TrackerTaskState.Working('other-instance-xyz'),
            new TrackerTaskState.Finished(),
            new TrackerTaskState.Gone(),
        ]
    }

    // FR10, NFR-R3 of add-claim-heartbeat: a tracker outage at the finish line retries with backoff
    // (virtual sleeper/clock, no real sleep) and confirms once the tracker returns.
    def "finish retries a tracker outage then delivers once the write lands"() {
        given:
        def now = new AtomicReference<Instant>(Instant.parse('2026-01-01T00:00:00Z'))
        Clock clock = { -> now.get() } as Clock
        Sleeper sleeper = { Duration d ->
            now.set(now.get().plus(d))
        } as Sleeper
        def retry = new TerminalWriteRetry(sleeper, clock, Duration.ofMinutes(10))
        def attempts = new AtomicInteger()
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))
        tracker.finish(REF, _ as String) >> {
            if (attempts.getAndIncrement() < 2) {
                throw new TrackerUnavailableException('tracker down')
            }
        }
        def completed = new TaskOutcome.Completed(STATE)

        when:
        def result = TakeFinishReport.finish(completed, CONTEXT, BRANCH, tracker, REF, INSTANCE, retry)

        then:
        attempts.get() == 3
        result instanceof TakeResult.Delivered
    }

    // FR10, D10: a tracker down past the bound gives up but the run still returns Delivered — the
    // branch carries the delivered outcome and reconcile-on-resume closes the missing finish.
    def "finish give-up past the bound still returns Delivered for reconcile to complete"() {
        given: 'a clock that self-advances two minutes each read (independently of the sleeper), so the'
        // bound is reached in a bounded number of polls even when a mutant drops sleeper.sleep or halves
        // the backoff — the give-up loop terminates instead of hanging. The killing tests for those
        // mutants live in TerminalWriteRetrySpec (exact backoff schedule + transient-outage confirm).
        def ticking = new AtomicReference<Instant>(Instant.parse('2026-01-01T00:00:00Z'))
        Clock clock = {
            ->
            def t = ticking.get()
            ticking.set(t.plus(Duration.ofMinutes(2)))
            t
        } as Clock
        Sleeper sleeper = { Duration d -> } as Sleeper
        def retry = new TerminalWriteRetry(sleeper, clock, Duration.ofMinutes(10))
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))
        tracker.finish(REF, _ as String) >> {
            throw new TrackerUnavailableException('still down')
        }
        def completed = new TaskOutcome.Completed(STATE)
        def result = null

        when:
        def events = capture {
            result = TakeFinishReport.finish(completed, CONTEXT, BRANCH, tracker, REF, INSTANCE, retry)
        }

        then:
        result instanceof TakeResult.Delivered
        (result as TakeResult.Delivered).summary().contains(BRANCH)

        and: 'exactly one ERROR names the task, its unreconciled write, and the reconcile hand-off'
        def errors = events.findAll { it.level == Level.ERROR }
        errors.size() == 1
        errors[0].formattedMessage.contains('PROJ-1')
        errors[0].formattedMessage.contains('could not be written before the retry bound')
        errors[0].formattedMessage.contains('reconcile')
    }

    /** A retry whose clock self-advances past the bound, so a persistent outage gives up promptly. */
    private static TerminalWriteRetry givingUpRetry() {
        def ticking = new AtomicReference<Instant>(Instant.parse('2026-01-01T00:00:00Z'))
        Clock clock = {
            ->
            def t = ticking.get()
            ticking.set(t.plus(Duration.ofMinutes(2)))
            t
        } as Clock
        Sleeper sleeper = { Duration d -> } as Sleeper
        new TerminalWriteRetry(sleeper, clock, Duration.ofMinutes(10))
    }

    // FR10 of harden-task-branch-contract: a recovered completion probes the tracker before
    // re-driving the write — a task already finished there needs no second finish, only the
    // destructive last step it still owes.
    def "a recovered completion whose finish already landed runs only the cleanup"() {
        given:
        def cleaned = new AtomicInteger()
        tracker.fetchTask(REF) >> new TrackerTask(
                REF, new TaskSnapshot(REF.id(), 'title', 'body'),
                new TrackerTaskState.Finished(), AbortFacts.none(), true)

        when:
        def result = TakeFinishReport.finish(
                new TaskOutcome.Completed(STATE), CONTEXT, BRANCH, tracker, REF, INSTANCE,
                TerminalWriteRetry.system(),
                new FinishTransition.Recovered({ cleaned.incrementAndGet() }))

        then: 'no duplicate finish — the probe found the effect at the target'
        0 * tracker.finish(*_)
        cleaned.get() == 1
        result instanceof TakeResult.Delivered
    }

    // FR10: the destructive step runs only behind a confirmed effect — an unconfirmed finish leaves
    // the envelope in place, which is the CompletedUncleaned shape the next pickup recovers.
    def "an unconfirmed finish leaves the cleanup undone"() {
        given:
        def cleaned = new AtomicInteger()
        tracker.fetchTask(REF) >> taskWith(new TrackerTaskState.Working(INSTANCE.value()))
        tracker.finish(REF, _ as String) >> {
            throw new TrackerUnavailableException('tracker down')
        }

        when:
        TakeFinishReport.finish(
                new TaskOutcome.Completed(STATE), CONTEXT, BRANCH, tracker, REF, INSTANCE, givingUpRetry(),
                new FinishTransition.Fresh({}, { cleaned.incrementAndGet() }))

        then:
        cleaned.get() == 0
    }
}
