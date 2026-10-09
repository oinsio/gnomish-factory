package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.app.ParkPipelines
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.time.Instant

/**
 * The resumed write as a kill-point table row (FR7, NFR-R1, NFR-R2 of make-checkpoint-gate-durable,
 * design D4, D8): an {@code AttemptsExhausted} park delivered, returned by a human without a reply
 * and claimed again; the resumed write is its one durable step — the outcome consumed and the
 * attempts reset, together, in one commit.
 *
 * <p>A kill right after it freezes the reset state — {@code Created}, since the reset emptied the
 * stage's round history — whose recovery owner is the stage engine, reached through the
 * ordinary take resume: the return grants exactly the one attempt the limit
 * allows, the round runs, fails its check again, and parks. Neither that pickup nor the second
 * consumes the outcome again — the tip's outcome is {@code null}, so no route reaches the resumed
 * write — and the attempts the round used stay used.
 *
 * <p>Host medium only, for the reason {@link ReclaimKillPoints} gives: the recovery runs a real
 * round, and the container's round closes inside a box this daemon-free matrix cannot start (a box
 * over the scripted docker never lands its snapshot). The container's resumed write itself — the
 * one commit — is pinned on both media by {@code ConsumedOutcomeIdentitySpec}.
 */
final class ResumedKillPoints {

    private ResumedKillPoints() {}

    /**
     * @param medium the branch medium's name, as an assertion message shows it
     * @param worldFor {@code (PipelineDefinition) -> TakeKillPointWorld}: a fresh claimed task
     */
    static KillPointTransition transition(String medium, Closure worldFor) {
        def definition = ParkPipelines.escalating()
        new KillPointTransition(
                name: "${medium} resumed write of a returned escalation",
                steps: [
                    'the resumed commit'
                ],
                world: {
                    returnedEscalation(worldFor.call(definition) as TakeKillPointWorld)
                },
                step: { TakeKillPointWorld w, int index ->
                    w.store.resumeFrom(w.taskId, w.tipState().resetAttempts())
                },
                shape: { TakeKillPointWorld w -> w.shape() },
                pickup: { TakeKillPointWorld w -> w.pickup() },
                fingerprint: { TakeKillPointWorld w ->
                    w.fingerprint() + [state: w.tipStateJson()]
                },
                invariant: { TakeKillPointWorld w ->
                    assert w.subjects().count('gnomish: task resumed') == 1: 'the outcome was consumed more than once'
                    assert w.agentRounds.call() == 1: 'the return did not grant exactly one round'
                    assert w.tipState().attemptsUsed() == 1: 'the attempt the round used was reset again'
                },
                // Created, not InProgress: the reset empties the stage's round history, so the tip
                // classifies as a stage not yet run — the same owner, the stage engine (D8).
                frozenShapes: ['Created'],
                converged: 'Parked')
    }

    /** The premise: a round that failed its check, the exhausted park delivered and receipted, the return. */
    private static TakeKillPointWorld returnedEscalation(TakeKillPointWorld world) {
        world.roundCommit(TaskState.atStageStart('build').recordQualityFailure(new AttemptRecord(0,
                AttemptRecord.Result.QUALITY_FAILURE, Instant.parse('2026-10-08T09:00:00Z'), [], ExecutorUsage.none(),
                JudgeUsage.none(), [], Stop.none())))
        world.store.recordOutcome(world.taskId, new TaskOutcome.Escalated(world.tipState(),
                new EscalationReport.AttemptsExhausted(1)), TrackerWrite.OWED)
        world.tracker.park(world.ref, ParkReason.ESCALATION, 'stopped for a human')
        world.store.confirmTerminalWrite(world.taskId)
        world.returnedAndReclaimed()
        world
    }
}
