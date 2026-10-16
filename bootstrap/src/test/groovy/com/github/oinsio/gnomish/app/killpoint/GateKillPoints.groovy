package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.adapter.git.state.EscalationReportDto
import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto
import com.github.oinsio.gnomish.app.ParkPipelines
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
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
 * window make-checkpoint-gate-durable exists for, where the position (the gate) or the round record
 * (the stop) is the only durable trace of the stop.
 *
 * <p>The pickup is the next {@code take} as production sequences it ({@link
 * TakeKillPointWorld#pickUpAfterKill}, design D3 of fix-terminal-receipt-convergence). Before the
 * tracker park, the reaper returns the dead claim and a second instance claims the task: {@code
 * TakeLoadedBranchRoutes} parks a gate whose park is missing, the engine re-raises a recorded stop
 * with no port call, and the orphaned-park reconcile re-delivers a park that never landed — no agent
 * round runs. After the tracker park, the park is the human's to answer: they return the task, and
 * the second instance's pickup confirms the park from the tenure history and continues with the
 * return — the gate is approved, or the reply-less question is restated.
 */
final class GateKillPoints {

    static final String GATE = 'build'

    private static final String REPORT = 'stopped for a human'

    private static final String APPROVED = 'gnomish: task approved'

    private GateKillPoints() {}

    /**
     * A {@code manual} pass with a stage after it: before the return the next stage never runs, and
     * the gate holds; after it, the approval opens the gate and the next stage runs its one round —
     * to its own gate on the host, to a park in the container, whose box never closes a round.
     *
     * @param medium the branch medium's name, as an assertion message shows it
     * @param worldFor {@code (PipelineDefinition) -> TakeKillPointWorld}: a fresh claimed task
     */
    static KillPointTransition paused(String medium, Closure worldFor) {
        String afterReturn = medium == 'host' ? 'AwaitingApproval' : 'Parked'
        row("${medium} manual gate before a next stage", worldFor, ParkPipelines.pausing(), gated(), afterReturn, { TakeKillPointWorld w ->
            assert w.agentRounds.call() == 0: 'the pickup ran an agent round'
            assert w.tipState().position() == new Position.AwaitingApproval(GATE): 'the gate did not hold'
            assert !w.subjects().any {
                it.startsWith('gnomish: round deploy')
            }: 'the next stage ran'
        }, { TakeKillPointWorld w ->
            assert w.subjects().count(APPROVED) == 1: 'the returned gate was not approved exactly once'
            assert w.parksAroundTakeover() == [1, w.agentRounds.call()]: 'the park the human answered was posted again'
            assert w.agentRounds.call() == 1: 'the approved gate did not run the next stage once'
            assert w.tipState().position() != new Position.AwaitingApproval(GATE): 'the returned gate still holds'
        })
    }

    /**
     * A {@code manual} last stage: its pass is not the pipeline's end — the approval is (design D1)
     * — so no pickup before the return records {@code Completed}, and the one after it delivers the
     * task with no agent round.
     */
    static KillPointTransition lastStage(String medium, Closure worldFor) {
        row("${medium} manual gate on the last stage", worldFor, ParkPipelines.lastGate(), gated(), 'Delivered', { TakeKillPointWorld w ->
            assert w.agentRounds.call() == 0: 'the pickup ran an agent round'
            assert !(w.tipTask().outcome() instanceof TaskOutcomeDto.Completed): 'a manual last stage completed'
            assert !w.subjects().contains('gnomish: task completed'): 'a manual last stage completed'
            assert w.tipState().position() == new Position.AwaitingApproval(GATE)
        }, { TakeKillPointWorld w ->
            assert w.agentRounds.call() == 0: 'the approval of a last gate ran an agent round'
            assert w.subjects().count(APPROVED) == 1: 'the returned gate was not approved exactly once'
            assert w.subjects().count('gnomish: task completed') == 1: 'the approved task was not delivered once'
            assert w.parksAroundTakeover() == [1, 0]: 'the park the human answered was posted again'
            assert w.tracker.fetchTask(w.ref).state() instanceof TrackerTaskState.Finished
        })
    }

    /**
     * A gnome that asked: the stop rides the round record, and the pickup re-raises it (design D3).
     * A return without a reply is answered by restating the question (FR13 of add-tracker-port).
     */
    static KillPointTransition decisionNeeded(String medium, Closure worldFor) {
        Closure parkedQuestion = { TakeKillPointWorld w ->
            assert w.agentRounds.call() == 0: 'the pickup ran an agent round'
            def parked = w.tipTask().lastEscalation() as EscalationReportDto.DecisionNeeded
            assert parked.question() == w.recordedStop().question().raw(): 'the parked question is not the recorded one'
        }
        row("${medium} decision-needed stop", worldFor, ParkPipelines.asking(), asked(), 'Parked', parkedQuestion,
                parkedQuestion)
    }

    /**
     * @param afterReturn the shape the pickup converges to once a human returned the landed park
     * @param held what the pickup leaves where no human has answered yet
     * @param returned what it leaves once a human returned the task
     */
    private static KillPointTransition row(String name, Closure worldFor, PipelineDefinition definition,
            TaskState round, String afterReturn, Closure held, Closure returned) {
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
                pickup: { TakeKillPointWorld w -> w.pickUpAfterKill() },
                fingerprint: { TakeKillPointWorld w ->
                    w.fingerprint() + [state: w.tipStateJson()]
                },
                invariant: { TakeKillPointWorld w ->
                    assert w.tipTask()?.trackerWritePending() != Boolean.TRUE: "the park was not settled: ${w.subjects()}"
                    (w.humanReturned ? returned : held).call(w)
                },
                frozenShapes: [
                    asks ? 'InProgress' : parked,
                    parked,
                    parked,
                    parked
                ],
                convergedShapes: [
                    parked,
                    parked,
                    afterReturn,
                    afterReturn
                ])
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
