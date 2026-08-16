package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.time.Instant
import spock.lang.Specification

/**
 * StatusSnapshotHolder: a mutable, synchronized holder of the current TaskState,
 * attempt limit and LiveActivity, read via a StatusReport built at call time from
 * a caller-supplied TaskContext (design D7). Implements FR10, FR11, D7 of
 * add-manual-run.
 */
class StatusSnapshotHolderSpec extends Specification {

    private static TaskContext context() {
        new TaskContext('manual-20260716-143502-x7', 'Fix flaky spec', 'body text', [])
    }

    // D7: a fresh holder starts at the given initial state, attempt limit and idle activity
    def "starts at the given initial state, attempt limit and idle activity"() {
        given: 'a holder created with an initial state and attempt limit'
        def initial = TaskState.atStageStart('implement')
        def holder = new StatusSnapshotHolder(initial, 3)

        expect: 'the held state, attempt limit and activity are the starting values'
        holder.state() == initial
        holder.attemptLimit() == 3
        holder.activity() == LiveActivity.idle()
    }

    // D7: updateState replaces the held TaskState
    def "updateState replaces the held TaskState"() {
        given: 'a holder at the pipeline start'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)
        def advanced = TaskState.atStageStart('implement').advanceTo(new Position.AtStage('review'))

        when: 'the state is updated'
        holder.updateState(advanced)

        then: 'the held state reflects the update'
        holder.state() == advanced
    }

    // FR11, D7: updateAttemptLimit replaces the held attempt limit
    def "updateAttemptLimit replaces the held attempt limit"() {
        given: 'a holder at the pipeline start'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)

        when: 'the attempt limit is updated'
        holder.updateAttemptLimit(5)

        then: 'the held attempt limit reflects the update'
        holder.attemptLimit() == 5
    }

    // D7: updateActivity replaces the activity while preserving any held escalation and outcome
    def "updateActivity replaces the activity and preserves any already-held escalation and outcome"() {
        given: 'a holder that already recorded an escalation'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)
        def escalation = new EscalationReport.DecisionNeeded('Refactor or patch?', ['refactor', 'patch'])
        holder.recordEscalation(escalation)
        def executing = new Activity.Executing(Instant.EPOCH)

        when: 'the activity is updated'
        holder.updateActivity(executing)

        then: 'the activity changes but the escalation and outcome survive'
        holder.activity().activity() == executing
        holder.activity().lastEscalation() == escalation
        holder.activity().outcome() == new Outcome.Escalated(escalation)
    }

    // D7: recordEscalation captures the report and its equivalent outcome, resets activity to idle
    def "recordEscalation captures the report and outcome and resets activity to idle"() {
        given: 'a holder mid-verification'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)
        holder.updateActivity(new Activity.Verifying(new CheckRef(0, 'builtin:files_exist'), Instant.EPOCH))
        def escalation = new EscalationReport.AttemptsExhausted(3)

        when: 'an escalation is recorded'
        holder.recordEscalation(escalation)

        then: 'the escalation and equivalent outcome are captured and activity resets to idle'
        holder.lastEscalation() == escalation
        holder.outcome() == new Outcome.Escalated(escalation)
        holder.activity().activity() == null
    }

    // FR11, D7: recordOutcome captures a non-escalated terminal outcome and resets activity to idle
    def "recordOutcome captures the outcome and resets activity to idle"() {
        given: 'a holder mid-execution'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'), 3)
        holder.updateActivity(new Activity.Executing(Instant.EPOCH))

        when: 'a Completed outcome is recorded'
        holder.recordOutcome(new Outcome.Completed())

        then: 'the outcome is captured and activity resets to idle'
        holder.outcome() == new Outcome.Completed()
        holder.activity().activity() == null
    }

    // D7: current(context) builds a StatusReport reflecting the held state, attempt limit and activity
    def "current builds a StatusReport reflecting the held state, attempt limit and activity"() {
        given: 'a holder with an updated state and activity'
        def state = TaskState.atStageStart('implement')
        def holder = new StatusSnapshotHolder(state, 3)
        def executing = new Activity.Executing(Instant.EPOCH)
        holder.updateActivity(executing)
        def ctx = context()

        when: 'a report is requested'
        def report = holder.current(ctx)

        then: 'the report matches a report built directly from the same inputs'
        report == StatusReport.build(ctx, state, 3, new LiveActivity(executing, null, null))
    }
}
