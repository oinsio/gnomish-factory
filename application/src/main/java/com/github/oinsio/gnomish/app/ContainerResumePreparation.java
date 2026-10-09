package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.PendingVerification;
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport;
import com.github.oinsio.gnomish.domain.engine.Position;
import org.jspecify.annotations.Nullable;

/**
 * The one container resume preparation (design D6, FR18 of make-checkpoint-gate-durable): what a
 * sandboxed resume does to the task's box before the engine continues from a recorded position.
 * Used by both commands — {@code run --resume} ({@link ContainerResumeOutcomes#resumeFromRecordedPosition})
 * and {@code take} ({@link TakeContainerResumeRunner#resumeWithoutDecision}) — each of which then
 * drives the run its own way. It is the only caller of {@link SandboxRunSupport#reattachFor}.
 *
 * <p>Stateless, like its neighbours {@link CheckpointApproval} and {@link ContainerResumeOutcomes}:
 * the support it acts on is the per-run bundle of the task being resumed, so it is a per-call
 * argument rather than equipment held across calls.
 *
 * <p>Implements FR18 of make-checkpoint-gate-durable; FR6, FR21 of add-sandbox-core; NFR-R4 of
 * add-serve-sandbox-lifecycle.
 */
final class ContainerResumePreparation {

    private ContainerResumePreparation() {}

    /**
     * Prepares the box in three steps, in order. First the snapshot check: a snapshot commit
     * unrecorded in {@code state.json} is an interrupted verification (FR21 of add-sandbox-core) —
     * the round is complete on the branch. Then, under {@code --discard-work}, whatever environment
     * survives is disposed so the next materialize seeds a fresh clone at the recorded tip;
     * otherwise the box is reattached for the stage of the position (start a stopped box, recreate
     * over a surviving volume, or seed a fresh clone) so both the salvage and same-box verification
     * of a pending snapshot have a live box. Last, only when no snapshot is pending, the
     * interrupted round's uncommitted leftovers are salvaged in-box — never over a finished round.
     *
     * @param support the sandbox run support bound to the task's branch; never null
     * @param discardWork {@code true} for {@code --discard-work}
     * @param position the recorded position the resume starts from; never null
     * @param taskId the task whose leftovers a salvage commits
     * @return the pending verification the drive re-verifies, or {@code null} when the tip is not
     *     a snapshot commit
     */
    static @Nullable PendingVerification prepare(
            SandboxRunSupport support, boolean discardWork, Position position, String taskId) {
        PendingVerification pending = support.pendingVerification().orElse(null);
        if (discardWork) {
            support.disposeExistingEnvironment();
            return pending;
        }
        String stage = stageToReattach(position);
        if (stage != null) {
            support.reattachFor(stage);
            if (pending == null) {
                support.salvageLeftovers(taskId);
            }
        }
        return pending;
    }

    /**
     * The stage whose box a resume reattaches: the stage the position names, and at a gate the
     * {@code manual} stage that passed — the box of the stage whose round the gate's commit
     * recorded (FR1 of make-checkpoint-gate-durable). Past the pipeline's end there is no stage and
     * nothing to reattach.
     */
    private static @Nullable String stageToReattach(Position position) {
        return switch (position) {
            case Position.AtStage(String stage) -> stage;
            case Position.AwaitingApproval(String gate) -> gate;
            case Position.PipelineEnd() -> null;
        };
    }
}
