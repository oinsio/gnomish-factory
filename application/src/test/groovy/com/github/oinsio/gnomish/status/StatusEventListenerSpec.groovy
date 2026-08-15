package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.port.Clock
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * StatusEventListener: an EngineEventListener adapter that keeps a
 * StatusSnapshotHolder current from the engine's event stream (design D7):
 * AttemptFinished carries the new state; AttemptStarted/CheckStarted drive
 * activity; a TaskFinished captures the terminal Outcome for all four
 * TaskOutcome kinds, plus the EscalationReport when Escalated. Implements
 * FR10, FR11, D7 of add-manual-run.
 */
class StatusEventListenerSpec extends Specification {

    private static final String TASK_ID = 'manual-20260716-143502-x7'
    private static final Instant STARTED = Instant.parse('2026-07-16T14:35:10Z')
    private static final Instant NOW = Instant.parse('2026-07-16T14:41:02Z')

    private static Clock fixedClock(Instant instant = NOW) {
        new Clock() {
                    @Override
                    Instant now() {
                        instant
                    }
                }
    }

    private static AttemptKey key(int attempt = 0, String stage = 'implement') {
        new AttemptKey(TASK_ID, stage, attempt)
    }

    private static TaskContext context() {
        new TaskContext(TASK_ID, 'Fix flaky spec', 'body text', [])
    }

    // FR10, FR11, D7: AttemptStarted sets activity to Executing stamped with the clock's now()
    def "AttemptStarted sets activity to Executing stamped with the clock instant"() {
        given: 'a listener wrapping a fresh holder and a fixed clock'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)
        def listener = new StatusEventListener(holder, fixedClock())

        when: 'an AttemptStarted event arrives'
        listener.onEvent(new EngineEvent.AttemptStarted(key()))

        then: 'the held activity is Executing at the clock instant'
        holder.activity().activity() == new Activity.Executing(NOW)
    }

    // FR10, FR11, D7: CheckStarted sets activity to Verifying naming the checkRef
    def "CheckStarted sets activity to Verifying naming the checkRef"() {
        given: 'a listener wrapping a fresh holder and a fixed clock'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)
        def listener = new StatusEventListener(holder, fixedClock())
        def checkRef = new CheckRef(0, 'builtin:files_exist')

        when: 'a CheckStarted event arrives'
        listener.onEvent(new EngineEvent.CheckStarted(key(), checkRef))

        then: 'the held activity is Verifying that check at the clock instant'
        holder.activity().activity() == new Activity.Verifying(checkRef, NOW)
    }

    // FR10, D7: ExecutionFinished is a no-op; the following CheckStarted sets the concrete activity
    def "ExecutionFinished leaves activity unchanged"() {
        given: 'a listener wrapping a holder already Executing'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)
        def listener = new StatusEventListener(holder, fixedClock())
        listener.onEvent(new EngineEvent.AttemptStarted(key()))

        when: 'an ExecutionFinished event arrives'
        listener.onEvent(new EngineEvent.ExecutionFinished(key(), ExecutorUsage.none()))

        then: 'the held activity is still Executing'
        holder.activity().activity() == new Activity.Executing(NOW)
    }

    // FR10, D7: AttemptFinished updates the holder's state to the event's newState
    def "AttemptFinished updates the held state to the event's newState"() {
        given: 'a listener wrapping a fresh holder'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)
        def listener = new StatusEventListener(holder, fixedClock())
        def check = new CheckResult(new CheckRef(0, 'builtin:files_exist'), new Verdict.Pass(), Duration.ofMillis(3))
        def round = new AttemptRecord(0, AttemptRecord.Result.PASSED, STARTED, [check],
        ExecutorUsage.none(), JudgeUsage.none())
        def newState = TaskState.atStageStart('implement').recordUnburnedRound(round)

        when: 'an AttemptFinished event arrives'
        listener.onEvent(new EngineEvent.AttemptFinished(key(), newState, new ToolTrace(key(), [])))

        then: 'the held state is the event newState'
        holder.state() == newState
    }

    // FR10, FR11, D7: TaskFinished(Escalated) captures the report and its outcome, resets activity
    def "TaskFinished with an Escalated outcome captures the report and outcome, resets activity"() {
        given: 'a listener wrapping a holder mid-verification'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)
        def listener = new StatusEventListener(holder, fixedClock())
        listener.onEvent(new EngineEvent.AttemptStarted(key()))
        def escalation = new EscalationReport.AttemptsExhausted(3)
        def outcome = new TaskOutcome.Escalated(TaskState.atStageStart('implement'), escalation)

        when: 'a TaskFinished event carrying the escalation arrives'
        listener.onEvent(new EngineEvent.TaskFinished(TASK_ID, outcome))

        then: 'the escalation and equivalent outcome are captured and activity resets'
        holder.lastEscalation() == escalation
        holder.outcome() == new Outcome.Escalated(escalation)
        holder.activity().activity() == null
    }

    // FR11, D7: TaskFinished(Completed) records the Completed outcome, no escalation
    def "TaskFinished with a Completed outcome records the outcome and no escalation"() {
        given: 'a listener wrapping a fresh holder'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)
        def listener = new StatusEventListener(holder, fixedClock())
        def outcome = new TaskOutcome.Completed(TaskState.atStageStart('implement'))

        when: 'a TaskFinished event carrying a Completed outcome arrives'
        listener.onEvent(new EngineEvent.TaskFinished(TASK_ID, outcome))

        then: 'the Completed outcome is recorded, no escalation'
        holder.outcome() == new Outcome.Completed()
        holder.lastEscalation() == null
    }

    // FR11, D7: TaskFinished(Paused) records the Paused outcome naming the passed stage
    def "TaskFinished with a Paused outcome records the outcome naming the passed stage"() {
        given: 'a listener wrapping a fresh holder'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('review'), 3)
        def listener = new StatusEventListener(holder, fixedClock())
        def outcome = new TaskOutcome.Paused(TaskState.atStageStart('review'), 'implement')

        when: 'a TaskFinished event carrying a Paused outcome arrives'
        listener.onEvent(new EngineEvent.TaskFinished(TASK_ID, outcome))

        then: 'the Paused outcome is recorded'
        holder.outcome() == new Outcome.Paused('implement')
    }

    // FR11, D7: TaskFinished(Aborted) records the Aborted outcome with failedAt and cause
    def "TaskFinished with an Aborted outcome records the outcome with failedAt and cause"() {
        given: 'a listener wrapping a fresh holder'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)
        def listener = new StatusEventListener(holder, fixedClock())
        def failedAt = key()
        def outcome = new TaskOutcome.Aborted(TaskState.atStageStart('implement'), failedAt, 'disk full')

        when: 'a TaskFinished event carrying an Aborted outcome arrives'
        listener.onEvent(new EngineEvent.TaskFinished(TASK_ID, outcome))

        then: 'the Aborted outcome is recorded'
        holder.outcome() == new Outcome.Aborted(failedAt, 'disk full')
    }

    // FR10, D7: RunStarted and CheckFinished are no-ops for this listener's scope
    def "RunStarted and CheckFinished leave state and activity unchanged"() {
        given: 'a listener wrapping a holder already Executing'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)
        def listener = new StatusEventListener(holder, fixedClock())
        listener.onEvent(new EngineEvent.AttemptStarted(key()))
        def check = new CheckResult(new CheckRef(0, 'builtin:files_exist'), new Verdict.Pass(), Duration.ofMillis(3))

        when: 'RunStarted and CheckFinished events arrive'
        listener.onEvent(new EngineEvent.RunStarted(TASK_ID, new Position.AtStage('implement'), 0))
        listener.onEvent(new EngineEvent.CheckFinished(key(), check))

        then: 'activity and state are unchanged'
        holder.activity().activity() == new Activity.Executing(NOW)
        holder.state() == TaskState.atStageStart('implement')
    }

    // FR10, FR11, D7: a full sequence of events produces the expected StatusReport
    def "a sequence of events produces the expected StatusReport"() {
        given: 'a listener wrapping a fresh holder and a task context'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)
        def listener = new StatusEventListener(holder, fixedClock())
        def ctx = context()
        def check = new CheckResult(new CheckRef(0, 'builtin:files_exist'), new Verdict.Pass(), Duration.ofMillis(3))
        def round = new AttemptRecord(0, AttemptRecord.Result.PASSED, STARTED, [check],
        ExecutorUsage.none(), JudgeUsage.none())
        def newState = TaskState.atStageStart('implement').recordUnburnedRound(round)
        def escalation = new EscalationReport.DecisionNeeded('Refactor or patch?', ['refactor', 'patch'])
        def outcome = new TaskOutcome.Escalated(newState, escalation)

        when: 'a realistic sequence of events arrives'
        listener.onEvent(new EngineEvent.RunStarted(TASK_ID, new Position.AtStage('implement'), 0))
        listener.onEvent(new EngineEvent.AttemptStarted(key()))
        listener.onEvent(new EngineEvent.ExecutionFinished(key(), ExecutorUsage.none()))
        listener.onEvent(new EngineEvent.CheckStarted(key(), new CheckRef(0, 'builtin:files_exist')))
        listener.onEvent(new EngineEvent.CheckFinished(key(), check))
        listener.onEvent(new EngineEvent.AttemptFinished(key(), newState, new ToolTrace(key(), [])))
        listener.onEvent(new EngineEvent.TaskFinished(TASK_ID, outcome))

        then: 'the report built from the holder reflects the final state, escalation and outcome'
        def report = holder.current(ctx)
        report == StatusReport.build(ctx, newState, 3, new LiveActivity(null, escalation, new Outcome.Escalated(escalation)))
        report.attempts() == [round]
        report.lastEscalation() == escalation
        report.outcome() == new Outcome.Escalated(escalation)
        report.activity() == null
    }
}
