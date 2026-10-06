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
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * StatusEventListener: an EngineEventListener adapter that keeps the live event
 * fold (StatusSnapshotHolder) current from the engine's event stream (design D7):
 * AttemptFinished carries the persisted round's state and the fold takes it; every
 * other event changes nothing. Implements FR10, FR11, D7 of add-manual-run; FR6 of
 * make-run-headless.
 */
class StatusEventListenerSpec extends Specification {

    private static final String TASK_ID = 'manual-20260716-143502-x7'
    private static final Instant STARTED = Instant.parse('2026-07-16T14:35:10Z')

    private static AttemptKey key(int attempt = 0, String stage = 'implement') {
        new AttemptKey(TASK_ID, stage, attempt)
    }

    private static CheckResult passingCheck() {
        new CheckResult(new CheckRef(0, UntrustedText.manifest('builtin:files_exist')), new Verdict.Pass(), Duration.ofMillis(3))
    }

    // FR10, FR11, D7 of add-manual-run; FR6 of make-run-headless: at an attempt boundary the fold
    // holds exactly the TaskState the round persisted, which AttemptFinished carries
    def "AttemptFinished updates the held state to the persisted round's state"() {
        given: 'a listener wrapping a fresh holder'
        def holder = new StatusSnapshotHolder(TaskState.atStageStart('implement'))
        def listener = new StatusEventListener(holder)
        def round = new AttemptRecord(0, AttemptRecord.Result.PASSED, STARTED, [passingCheck()],
        ExecutorUsage.none(), JudgeUsage.none(), [])
        def newState = TaskState.atStageStart('implement').recordUnburnedRound(round)

        when: 'an AttemptFinished event arrives'
        listener.onEvent(new EngineEvent.AttemptFinished(key(), newState, new ToolTrace(key(), [])))

        then: 'the held state is the event newState'
        holder.state() == newState
        holder.state().attempts() == [round]
    }

    // FR10, D7 of add-manual-run; FR6 of make-run-headless: no other event carries a state change
    def "every event but AttemptFinished leaves the held state unchanged"() {
        given: 'a listener wrapping a fresh holder'
        def initial = TaskState.atStageStart('implement')
        def holder = new StatusSnapshotHolder(initial)
        def listener = new StatusEventListener(holder)

        when: 'the event arrives'
        listener.onEvent(event)

        then: 'the held state is still the initial one'
        holder.state() == initial

        where:
        event << [
            new EngineEvent.RunStarted(TASK_ID, new Position.AtStage('implement'), 0),
            new EngineEvent.AttemptStarted(key()),
            new EngineEvent.ExecutionFinished(key(), ExecutorUsage.none()),
            new EngineEvent.CheckStarted(key(), new CheckRef(0, UntrustedText.manifest('builtin:files_exist'))),
            new EngineEvent.CheckFinished(key(), passingCheck()),
            new EngineEvent.TaskFinished(TASK_ID, new TaskOutcome.Completed(TaskState.atStageStart('review'))),
            new EngineEvent.TaskFinished(TASK_ID, new TaskOutcome.Paused(TaskState.atStageStart('review'), 'implement')),
            new EngineEvent.TaskFinished(TASK_ID,
            new TaskOutcome.Escalated(TaskState.atStageStart('review'), new EscalationReport.AttemptsExhausted(3))),
            new EngineEvent.TaskFinished(TASK_ID, new TaskOutcome.Aborted(TaskState.atStageStart('review'),
            new AttemptKey(TASK_ID, 'implement', 0), UntrustedText.subprocess('disk full')))
        ]
    }
}
