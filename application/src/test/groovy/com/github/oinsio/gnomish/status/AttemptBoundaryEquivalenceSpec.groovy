package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.port.Clock
import java.time.Instant
import spock.lang.Specification

/**
 * status-report capability, "Fields partitioned by derivability" requirement,
 * "Attempt boundary equivalence" scenario: a report built live from the engine's
 * event stream at an attempt boundary SHALL equal a report built fresh from the
 * same task's {@code (TaskContext, TaskState)} with no live signal — the property
 * that lets an external consumer reproduce the exact live document from a
 * persisted state file alone (openspec/changes/add-manual-run
 * specs/status-report/spec.md).
 *
 * <p>"Attempt boundary" is the instant right after an {@code AttemptFinished}
 * event has been processed and before the next {@code AttemptStarted}/{@code
 * CheckStarted} fires — the engine is momentarily quiescent between rounds. For
 * the equivalence to hold, the live side's activity must genuinely be idle at
 * that instant; this spec's fixture inspects {@link StatusEventListener}'s
 * handling of {@code AttemptFinished}, which now resets activity to idle there
 * (see {@code StatusEventListener} javadoc) precisely so this property holds.
 *
 * FR11, D7 of add-manual-run.
 */
class AttemptBoundaryEquivalenceSpec extends Specification {

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
        new TaskContext(TASK_ID, 'Fix flaky OrderServiceSpec', 'body text',
                [
                    new Decision('patch in place', 'plan', 'operator', STARTED)
                ])
    }

    // FR11, D7: report built from events equals report built from (context, state) at an attempt boundary
    def "report built from events equals report built from context and state at an attempt boundary"() {
        given: 'a fresh holder and listener at the start of a stage'
        def initialState = TaskState.atStageStart('implement')
        def holder = new StatusSnapshotHolder(initialState, 3)
        def listener = new StatusEventListener(holder, fixedClock())
        def ctx = context()

        and: 'the check result and round that AttemptFinished will carry'
        def checkRef = new CheckRef(0, 'command:./gradlew test')
        def checkResult = new CheckResult(checkRef, new Verdict.Pass(), java.time.Duration.ofMillis(41250))
        def round = new AttemptRecord(0, AttemptRecord.Result.PASSED, STARTED, [checkResult],
        ExecutorUsage.none(), JudgeUsage.none())
        def newState = initialState.recordUnburnedRound(round)

        when: 'a full round runs, stopping exactly at the attempt boundary: right after AttemptFinished'
        listener.onEvent(new EngineEvent.RunStarted(TASK_ID, new Position.AtStage('implement'), 0))
        listener.onEvent(new EngineEvent.AttemptStarted(key()))
        listener.onEvent(new EngineEvent.ExecutionFinished(key(), ExecutorUsage.none()))
        listener.onEvent(new EngineEvent.CheckStarted(key(), checkRef))
        listener.onEvent(new EngineEvent.CheckFinished(key(), checkResult))
        listener.onEvent(new EngineEvent.AttemptFinished(key(), newState, new ToolTrace(key(), [])))

        and: 'report A is the live, event-built report at that boundary'
        def reportA = holder.current(ctx)

        and: 'report B is built fresh from the same (context, state) with no live signal at all'
        def reportB = StatusReport.build(ctx, holder.state(), holder.attemptLimit(), LiveActivity.idle())

        then: 'the engine is genuinely idle at the boundary: AttemptFinished reset live activity'
        holder.activity() == LiveActivity.idle()

        and: 'the two reports are equal, as the scenario requires'
        reportA == reportB
    }
}
