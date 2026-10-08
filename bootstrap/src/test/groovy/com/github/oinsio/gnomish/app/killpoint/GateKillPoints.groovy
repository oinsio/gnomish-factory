package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.state.EscalationReportDto
import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto
import com.github.oinsio.gnomish.app.ParkPipelines
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant

/**
 * The gate as kill-point table rows (FR4, FR5, FR11, NFR-R1 of make-checkpoint-gate-durable, design
 * D8): a stage that stops — a {@code manual} pass, or a gnome that asks — lands 1. its round
 * commit, 2. the park outcome commit carrying the pending marker, 3. the tracker park, 4. the
 * receipt clearing the marker. The kill after step 1 is the barrier before the park commit: the
 * window this change exists for, where the position (the gate) or the round record (the stop) is
 * the only durable trace of the stop.
 *
 * <p>The pickup is a real {@code take} through the medium's routes ({@link TakeKillPointWorld#pickup}).
 * Its recovery owners, per window: after 1, {@code TakeLoadedBranchRoutes} parks a gate whose park
 * is missing without running a stage, and the engine re-raises a recorded stop with no port call;
 * after 2 and 3, the orphaned-park reconcile re-delivers or confirms. Every window converges to the
 * delivered park, and the recording executor shows that no agent round — of the gated stage or the
 * next — ever ran.
 */
final class GateKillPoints {

    static final String GATE = 'build'

    private static final String REPORT = 'stopped for a human'

    private GateKillPoints() {}

    /**
     * A {@code manual} pass with a stage after it: the next stage never runs, and the gate holds.
     *
     * @param medium the branch medium's name, as an assertion message shows it
     * @param worldFor {@code (PipelineDefinition) -> TakeKillPointWorld}: a fresh claimed task
     */
    static KillPointTransition paused(String medium, Closure worldFor) {
        row("${medium} manual gate before a next stage", worldFor, ParkPipelines.pausing(), gated()) { TakeKillPointWorld w ->
            assert w.tipState().position() == new Position.AwaitingApproval(GATE): 'the gate did not hold'
            assert !w.subjects().any {
                it.startsWith('gnomish: round deploy')
            }: 'the next stage ran'
        }
    }

    /**
     * A {@code manual} last stage: its pass is not the pipeline's end — the approval is — so no
     * pickup of any window records {@code Completed} (design D1).
     */
    static KillPointTransition lastStage(String medium, Closure worldFor) {
        row("${medium} manual gate on the last stage", worldFor, ParkPipelines.lastGate(), gated()) { TakeKillPointWorld w ->
            assert !(w.tipTask().outcome() instanceof TaskOutcomeDto.Completed): 'a manual last stage completed'
            assert !w.subjects().contains('gnomish: task completed'): 'a manual last stage completed'
            assert w.tipState().position() == new Position.AwaitingApproval(GATE)
        }
    }

    /** A gnome that asked: the stop rides the round record, and the pickup re-raises it (design D3). */
    static KillPointTransition decisionNeeded(String medium, Closure worldFor) {
        row("${medium} decision-needed stop", worldFor, ParkPipelines.asking(), asked()) { TakeKillPointWorld w ->
            def parked = w.tipTask().lastEscalation() as EscalationReportDto.DecisionNeeded
            assert parked.question() == w.recordedStop().question().raw(): 'the parked question is not the recorded one'
        }
    }

    private static KillPointTransition row(String name, Closure worldFor, PipelineDefinition definition,
            TaskState round, Closure assertions) {
        // A recorded stop parks as an escalation from a round at its stage; a pass parks at the gate.
        boolean asks = round.attempts().last().stop() instanceof Stop.DecisionNeeded
        def reason = asks ? ParkReason.ESCALATION : ParkReason.CHECKPOINT
        String parked = asks ? 'Parked' : 'AwaitingApproval'
        new KillPointTransition(
                name: name,
                steps: [
                    'the round commit (the barrier before the park commit)',
                    'the park outcome commit carrying the pending marker',
                    'the tracker park',
                    'the receipt clearing the pending marker',
                ],
                world: { worldFor.call(definition) },
                step: { TakeKillPointWorld w, int index ->
                    step(w, index, round, reason)
                },
                shape: { TakeKillPointWorld w -> w.shape() },
                pickup: { TakeKillPointWorld w -> w.pickup() },
                fingerprint: { TakeKillPointWorld w ->
                    w.fingerprint() + [state: w.tipStateJson()]
                },
                invariant: { TakeKillPointWorld w ->
                    assert w.agentRounds.call() == 0: 'the pickup ran an agent round'
                    assert w.tipTask().trackerWritePending() != Boolean.TRUE: "the park was not settled: ${w.subjects()}"
                    assertions.call(w)
                },
                frozenShapes: [
                    asks ? 'InProgress' : parked,
                    parked,
                    parked,
                    parked
                ],
                converged: parked)
    }

    private static void step(TakeKillPointWorld world, int index, TaskState round, ParkReason reason) {
        if (index == 0) {
            world.roundCommit(round)
        } else if (index == 1) {
            world.store.recordOutcome(world.taskId, outcome(world, reason), TrackerWrite.OWED)
        } else if (index == 2) {
            world.tracker.park(world.ref, reason, REPORT)
        } else {
            world.store.confirmTerminalWrite(world.taskId)
        }
    }

    private static TaskOutcome outcome(TakeKillPointWorld world, ParkReason reason) {
        reason == ParkReason.CHECKPOINT
                ? new TaskOutcome.Paused(world.tipState(), GATE)
                : new TaskOutcome.Escalated(world.tipState(), world.recordedStop())
    }

    /** The round commit of a {@code manual} pass: the pass recorded, the position at the gate. */
    static TaskState gated() {
        TaskState.atStageStart(GATE).recordPassAndAdvance(round(AttemptRecord.Result.PASSED, Stop.none()),
                new Position.AwaitingApproval(GATE))
    }

    private static TaskState asked() {
        def stop = new Stop.DecisionNeeded(UntrustedText.agent('which db?'), [
            UntrustedText.agent('pg'),
            UntrustedText.agent('sqlite')
        ])
        TaskState.atStageStart(GATE).recordUnburnedRound(round(AttemptRecord.Result.DECISION_NEEDED, stop))
    }

    private static AttemptRecord round(AttemptRecord.Result result, Stop stop) {
        new AttemptRecord(0, result, Instant.parse('2026-10-08T09:00:00Z'), [], ExecutorUsage.none(), JudgeUsage.none(), [], stop)
    }
}
