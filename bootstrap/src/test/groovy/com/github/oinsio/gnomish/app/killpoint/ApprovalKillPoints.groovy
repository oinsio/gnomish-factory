package com.github.oinsio.gnomish.app.killpoint

import com.github.oinsio.gnomish.app.ParkPipelines
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskOutcome

/**
 * The approval as a kill-point table row (FR4, NFR-R1, NFR-R2 of make-checkpoint-gate-durable,
 * design D2, D8): a {@code manual} last stage parked at its gate and delivered, returned by a human
 * and claimed again; the approval is its one durable step — the tip's position moves past the gate
 * and the outcome is cleared, in one commit.
 *
 * <p>A kill right after it freezes the ordinary interrupted-run shape at the position after the
 * gate — here the pipeline's end, since the gate was the last stage — and its recovery owner is
 * the ordinary take resume: the engine finds nothing left to run, and the task is delivered with no
 * agent round. The second pickup finds a delivered task and changes nothing: the approval is not
 * repeated, and no second completion lands.
 */
final class ApprovalKillPoints {

    private ApprovalKillPoints() {}

    /**
     * @param medium the branch medium's name, as an assertion message shows it
     * @param worldFor {@code (PipelineDefinition) -> TakeKillPointWorld}: a fresh claimed task
     */
    static KillPointTransition transition(String medium, Closure worldFor) {
        def definition = ParkPipelines.lastGate()
        new KillPointTransition(
                name: "${medium} approval of a returned gate",
                steps: [
                    'the approval commit'
                ],
                world: {
                    returnedGate(worldFor.call(definition) as TakeKillPointWorld)
                },
                step: { TakeKillPointWorld w, int index -> approve(w) },
                shape: { TakeKillPointWorld w -> w.shape() },
                pickup: { TakeKillPointWorld w -> w.pickup() },
                fingerprint: { TakeKillPointWorld w ->
                    w.fingerprint() + [state: w.tipStateJson()]
                },
                invariant: { TakeKillPointWorld w ->
                    assert w.agentRounds.call() == 0: 'the pickup ran an agent round past an approved last gate'
                    assert w.subjects().count('gnomish: task approved') == 1: 'the approval was not landed exactly once'
                    assert w.subjects().count('gnomish: task completed') == 1: 'the approved task was not delivered once'
                },
                frozenShapes: ['InProgress'],
                converged: 'Delivered')
    }

    /** The premise: the gate's round, its park delivered and receipted, then the human's return. */
    private static TakeKillPointWorld returnedGate(TakeKillPointWorld world) {
        world.roundCommit(GateKillPoints.gated())
        world.store.recordOutcome(world.taskId, new TaskOutcome.Paused(world.tipState(), GateKillPoints.GATE), TrackerWrite.OWED)
        world.tracker.park(world.ref, ParkReason.CHECKPOINT, 'stopped for a human')
        world.store.confirmTerminalWrite(world.taskId)
        world.returnedAndReclaimed()
        world
    }

    /** The approval write, from the gate on the tip and the state the pinned definition approves to. */
    private static void approve(TakeKillPointWorld world) {
        def gated = world.tipState()
        world.store.approveCheckpoint(world.taskId, gated.position() as Position.AwaitingApproval,
                gated.approveGate(world.definition))
    }
}
