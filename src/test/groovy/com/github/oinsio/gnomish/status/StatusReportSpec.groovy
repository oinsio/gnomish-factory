package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * StatusReport: a single report model built by a pure function of
 * (TaskContext, TaskState, attemptLimit, LiveActivity), fields partitioned
 * state-derivable (required) vs live-only (nullable) (design D7). Implements
 * FR10, FR11, D7 of add-manual-run.
 */
class StatusReportSpec extends Specification {

    private static final Instant STARTED = Instant.parse('2026-07-16T14:35:10Z')

    private static TaskContext context(List<Decision> decisions = []) {
        new TaskContext('manual-20260716-143502-x7', 'Fix flaky spec', 'body text', decisions)
    }

    private static AttemptRecord passedRound() {
        def check = new CheckResult(new CheckRef(0, 'builtin:files_exist'), new Verdict.Pass(), Duration.ofMillis(3))
        new AttemptRecord(0, AttemptRecord.Result.PASSED, STARTED, [check], ExecutorUsage.none(), JudgeUsage.none())
    }

    // FR11: a task positioned AtStage produces a non-null currentStage matching the stage name
    def "produces a non-null currentStage matching the stage name for a task AtStage"() {
        given: 'a state positioned at a named stage'
        def state = TaskState.atStageStart('implement')

        when: 'a report is built with no live activity'
        def report = StatusReport.build(context(), state, 3, LiveActivity.idle())

        then: 'currentStage names the stage'
        report.currentStage() == 'implement'
    }

    // FR11: currentStage is null at pipelineEnd
    def "produces a null currentStage for a task at pipelineEnd"() {
        given: 'a state advanced to the pipeline end'
        def state = TaskState.atStageStart('implement').advanceTo(new Position.PipelineEnd())

        when: 'a report is built'
        def report = StatusReport.build(context(), state, null, LiveActivity.idle())

        then: 'currentStage is null'
        report.currentStage() == null
    }

    // FR11: attemptLimit passes through the builder's explicit input, mirroring currentStage's lifecycle
    def "passes attemptLimit through from the builder input"() {
        given: 'a state positioned at a named stage'
        def state = TaskState.atStageStart('implement')

        when: 'a report is built with an explicit attempt limit'
        def report = StatusReport.build(context(), state, 5, LiveActivity.idle())

        then: 'attemptLimit reflects the builder input'
        report.attemptLimit() == 5
    }

    // FR11: attemptLimit is null at pipelineEnd, mirroring currentStage
    def "produces a null attemptLimit at pipelineEnd"() {
        given: 'a state advanced to the pipeline end'
        def state = TaskState.atStageStart('implement').advanceTo(new Position.PipelineEnd())

        when: 'a report is built with a null attempt limit'
        def report = StatusReport.build(context(), state, null, LiveActivity.idle())

        then: 'attemptLimit is null'
        report.attemptLimit() == null
    }

    // FR11: attemptsUsed, attempts, decisions, totals pass through faithfully from TaskState/TaskContext
    def "passes attemptsUsed, attempts, decisions and totals through from state and context"() {
        given: 'a state with a recorded quality failure and non-empty totals'
        def failedCheck = new CheckResult(new CheckRef(0, 'command:./gradlew test'),
                new Verdict.Fail([]), Duration.ofSeconds(5))
        def round = new AttemptRecord(0, AttemptRecord.Result.QUALITY_FAILURE, STARTED, [failedCheck],
        new ExecutorUsage(Duration.ofSeconds(5), [], null), JudgeUsage.none())
        def state = TaskState.atStageStart('implement').recordQualityFailure(round)
        def decision = new Decision('patch in place', 'plan', 'operator', STARTED)
        def ctx = context([decision])

        when: 'a report is built'
        def report = StatusReport.build(ctx, state, 3, LiveActivity.idle())

        then: 'the state-derivable fields pass through unchanged'
        report.taskId() == ctx.taskId()
        report.title() == ctx.title()
        report.body() == ctx.body()
        report.attemptsUsed() == 1
        report.attempts() == [round]
        report.decisions() == [decision]
        report.totals() == state.totals()
    }

    // FR11: lastDecision resolves to the last element of context.decisions()
    def "resolves lastDecision to the last recorded decision"() {
        given: 'a context with two chronological decisions'
        def first = new Decision('first', null, null, null)
        def second = new Decision('second', 'plan', 'operator', STARTED)
        def ctx = context([first, second])

        when: 'a report is built'
        def report = StatusReport.build(ctx, TaskState.atStageStart('implement'), 3, LiveActivity.idle())

        then: 'lastDecision is the most recent one'
        report.lastDecision() == second
    }

    // FR11: lastDecision is null when no decisions were recorded
    def "resolves lastDecision to null when decisions is empty"() {
        when: 'a report is built with no decisions'
        def report = StatusReport.build(context(), TaskState.atStageStart('implement'), 3, LiveActivity.idle())

        then: 'lastDecision is null'
        report.lastDecision() == null
    }

    // D7: LiveActivity.idle() produces a report with no activity, no outcome, no lastEscalation
    def "LiveActivity.idle produces a report with no activity, no outcome and null lastEscalation"() {
        given: 'a plain state'
        def state = TaskState.atStageStart('implement')

        when: 'a report is built with the idle sentinel'
        def report = StatusReport.build(context(), state, 3, LiveActivity.idle())

        then: 'the live-only fields reflect idle, no outcome, no escalation'
        report.activity() == null
        report.outcome() == null
        report.lastEscalation() == null
    }

    // D7: a LiveActivity carrying an escalation report surfaces it on the built StatusReport
    def "surfaces a carried escalation report and activity on the built StatusReport"() {
        given: 'a state and a live activity carrying an escalation report'
        def state = TaskState.atStageStart('implement')
        def escalation = new EscalationReport.DecisionNeeded('Refactor or patch?', ['refactor', 'patch'])
        def activity = new LiveActivity(new Activity.AwaitingInput('decide?', STARTED), escalation, null)

        when: 'a report is built'
        def report = StatusReport.build(context(), state, 3, activity)

        then: 'the escalation and activity are surfaced'
        report.activity() == new Activity.AwaitingInput('decide?', STARTED)
        report.lastEscalation() == escalation
    }

    // D7: a LiveActivity carrying a terminal outcome surfaces it on the built StatusReport
    def "surfaces a carried outcome on the built StatusReport"() {
        given: 'a state and a live activity carrying a Completed outcome'
        def state = TaskState.atStageStart('implement')
        def activity = new LiveActivity(null, null, new Outcome.Completed())

        when: 'a report is built'
        def report = StatusReport.build(context(), state, null, activity)

        then: 'the outcome is surfaced'
        report.outcome() == new Outcome.Completed()
    }

    // FR11: attempts is defensively copied and unmodifiable
    def "exposes attempts as unmodifiable"() {
        given: 'a report'
        def report = new StatusReport('t1', 'title', 'body', 'stage', 0, 3, [passedRound()], [], null,
        ExecutorUsage.none(), null, null, null)

        when: 'a caller tries to mutate the exposed list'
        report.attempts().add(passedRound())

        then: 'the mutation is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR11: decisions is defensively copied and unmodifiable
    def "exposes decisions as unmodifiable"() {
        given: 'a report'
        def decision = new Decision('do it', null, null, null)
        def report = new StatusReport('t1', 'title', 'body', 'stage', 0, 3, [], [decision], decision,
        ExecutorUsage.none(), null, null, null)

        when: 'a caller tries to mutate the exposed list'
        report.decisions().add(decision)

        then: 'the mutation is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR11, D7: StatusReport is inert value data compared by content
    def "is value-equal by content"() {
        given: 'a state and context'
        def state = TaskState.atStageStart('implement')
        def ctx = context()

        expect: 'two reports built from equal inputs are equal'
        StatusReport.build(ctx, state, 3, LiveActivity.idle()) == StatusReport.build(ctx, state, 3, LiveActivity.idle())

        and: 'a differing currentStage makes them unequal'
        StatusReport.build(ctx, state, 3, LiveActivity.idle()) !=
                StatusReport.build(ctx, TaskState.atStageStart('other'), 3, LiveActivity.idle())
    }
}
