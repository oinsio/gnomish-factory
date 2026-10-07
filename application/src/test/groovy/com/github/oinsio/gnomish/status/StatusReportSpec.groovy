package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * StatusReport: a single report model built by a pure function of
 * (TaskContext, TaskState) plus the recorded last escalation and outcome; every
 * field is read from persisted task state (design D7 of add-manual-run, D4 of
 * make-run-headless). Implements FR10, FR11, D7 of add-manual-run; FR6 of
 * make-run-headless; FR12 of make-checkpoint-gate-durable.
 */
class StatusReportSpec extends Specification {

    private static final Instant STARTED = Instant.parse('2026-07-16T14:35:10Z')

    private static TaskContext context(List<Decision> decisions = []) {
        new TaskContext('manual-20260716-143502-x7', UntrustedText.tracker('Fix flaky spec'), UntrustedText.tracker('body text'), decisions)
    }

    private static AttemptRecord passedRound() {
        def check = new CheckResult(new CheckRef(0, UntrustedText.manifest('builtin:files_exist')), new Verdict.Pass(), Duration.ofMillis(3))
        new AttemptRecord(0, AttemptRecord.Result.PASSED, STARTED, [check], ExecutorUsage.none(), JudgeUsage.none(), [], Stop.none())
    }

    // FR11: a task positioned AtStage produces a non-null currentStage matching the stage name
    def "produces a non-null currentStage matching the stage name for a task AtStage"() {
        given: 'a state positioned at a named stage'
        def state = TaskState.atStageStart('implement')

        when: 'a report is built from the state alone'
        def report = StatusReport.build(context(), state, null, null)

        then: 'currentStage names the stage'
        report.currentStage() == 'implement'
    }

    // FR11: currentStage is null at pipelineEnd
    def "produces a null currentStage for a task at pipelineEnd"() {
        given: 'a state advanced to the pipeline end'
        def state = TaskState.atStageStart('implement').advanceTo(new Position.PipelineEnd())

        when: 'a report is built'
        def report = StatusReport.build(context(), state, null, null)

        then: 'currentStage is null'
        report.currentStage() == null
    }

    // FR11: attemptsUsed, attempts, decisions, totals pass through faithfully from TaskState/TaskContext
    // FR12, UX1 of make-checkpoint-gate-durable: at a gate the report names the gate as its position
    //     and describes the stage that passed, its passing round last
    def "describes the stage that passed for a task held at a gate"() {
        given: 'a state at the gate of release, its passing round recorded'
        def state = new TaskState(new Position.AwaitingApproval('release'), 0, [passedRound()], ExecutorUsage.none())

        when: 'a report is built'
        def report = StatusReport.build(context(), state, null, null)

        then: 'the position is the gate and currentStage names the stage that passed'
        report.position() == new Position.AwaitingApproval('release')
        report.currentStage() == 'release'

        and: 'the passing round is the last attempt described'
        report.attempts().last().result() == AttemptRecord.Result.PASSED
    }

    def "passes attemptsUsed, attempts, decisions and totals through from state and context"() {
        given: 'a state with a recorded quality failure and non-empty totals'
        def failedCheck = new CheckResult(new CheckRef(0, UntrustedText.manifest('command:./gradlew test')),
                new Verdict.Fail([]), Duration.ofSeconds(5))
        def round = new AttemptRecord(0, AttemptRecord.Result.QUALITY_FAILURE, STARTED, [failedCheck],
        new ExecutorUsage(Duration.ofSeconds(5), [], [:]), JudgeUsage.none(), [], Stop.none())
        def state = TaskState.atStageStart('implement').recordQualityFailure(round)
        def decision = new Decision('patch in place', 'plan', 'operator', STARTED)
        def ctx = context([decision])

        when: 'a report is built'
        def report = StatusReport.build(ctx, state, null, null)

        then: 'the state-derived fields pass through unchanged'
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
        def report = StatusReport.build(ctx, TaskState.atStageStart('implement'), null, null)

        then: 'lastDecision is the most recent one'
        report.lastDecision() == second
    }

    // FR11: lastDecision is null when no decisions were recorded
    def "resolves lastDecision to null when decisions is empty"() {
        when: 'a report is built with no decisions'
        def report = StatusReport.build(context(), TaskState.atStageStart('implement'), null, null)

        then: 'lastDecision is null'
        report.lastDecision() == null
    }

    // D7 of add-manual-run; FR6 of make-run-headless: a report built from the state alone carries
    // no recorded outcome and no escalation
    def "a report built from the state alone has no outcome and no lastEscalation"() {
        when: 'a report is built with nothing recorded beside the state'
        def report = StatusReport.build(context(), TaskState.atStageStart('implement'), null, null)

        then: 'the recorded fields are absent'
        report.outcome() == null
        report.lastEscalation() == null
    }

    // D7 of add-manual-run; FR6 of make-run-headless: a recorded escalation surfaces on the report
    def "surfaces a recorded escalation report on the built StatusReport"() {
        given: 'a state and a recorded escalation report'
        def state = TaskState.atStageStart('implement')
        def escalation = new EscalationReport.DecisionNeeded(UntrustedText.agent('Refactor or patch?'), [
            UntrustedText.agent('refactor'),
            UntrustedText.agent('patch')
        ])

        when: 'a report is built'
        def report = StatusReport.build(context(), state, escalation, null)

        then: 'the escalation is surfaced and no outcome is invented'
        report.lastEscalation() == escalation
        report.outcome() == null
    }

    // D7 of add-manual-run; FR6 of make-run-headless: a recorded terminal outcome surfaces on the report
    def "surfaces a recorded outcome on the built StatusReport"() {
        when: 'a report is built with a recorded Completed outcome'
        def report = StatusReport.build(context(), TaskState.atStageStart('implement'), null, new Outcome.Completed())

        then: 'the outcome is surfaced and no escalation is invented'
        report.outcome() == new Outcome.Completed()
        report.lastEscalation() == null
    }

    // FR11: attempts is defensively copied and unmodifiable
    def "exposes attempts as unmodifiable"() {
        given: 'a report'
        def report = new StatusReport('t1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), new Position.AtStage('stage'), 0, [passedRound()], [], null,
        ExecutorUsage.none(), null, null)

        when: 'a caller tries to mutate the exposed list'
        report.attempts().add(passedRound())

        then: 'the mutation is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR11: decisions is defensively copied and unmodifiable
    def "exposes decisions as unmodifiable"() {
        given: 'a report'
        def decision = new Decision('do it', null, null, null)
        def report = new StatusReport('t1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), new Position.AtStage('stage'), 0, [], [decision], decision,
        ExecutorUsage.none(), null, null)

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
        StatusReport.build(ctx, state, null, null) == StatusReport.build(ctx, state, null, null)

        and: 'a differing currentStage makes them unequal'
        StatusReport.build(ctx, state, null, null) !=
                StatusReport.build(ctx, TaskState.atStageStart('other'), null, null)
    }
}
