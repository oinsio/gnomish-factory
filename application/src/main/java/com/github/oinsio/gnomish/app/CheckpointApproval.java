package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;

/**
 * The one way a resume opens a gate (design D2, D7 of make-checkpoint-gate-durable): the approved
 * state is computed through {@link TaskState#approveGate} against the pinned definition, and the
 * gate handed to {@link TaskRepository#approveCheckpoint} is the one read off the tip — pattern
 * matched from the gated state, never constructed. Shared by the four callers that hold a pinned
 * definition: the two {@code run} continuations ({@link GitResumeContinuation#resumePaused}, {@link
 * ContainerResumeOutcomes#resumePaused}) and the two {@code take} mechanics ({@link
 * HostResumeMechanics#approveCheckpoint}, {@link ContainerResumeMechanics#approveCheckpoint}).
 *
 * <p>Implements FR3, FR4, FR7 of make-checkpoint-gate-durable.
 */
final class CheckpointApproval {

    private CheckpointApproval() {}

    /**
     * Lands the approval commit and returns the state the engine continues from.
     *
     * @param repository the medium's lifecycle writer; never null
     * @param taskId the task whose branch is at the gate
     * @param gated the state read off the tip; its position is a gate
     * @param definition the task's pinned pipeline; the gate's stage is resolved against it
     * @param beforeWrite run once the approved state is computed and before the commit — the
     *     container medium disposes its kept box there, so a refused computation touches nothing
     * @return the approved state, past the gate; never itself a gate
     * @throws IllegalStateException when {@code gated} is not at a gate, or its stage is not declared
     *     in {@code definition}; nothing was written
     * @throws com.github.oinsio.gnomish.app.port.CheckpointApprovalRefusedException when the tip no
     *     longer sits at this gate; nothing was written
     */
    static TaskState approve(
            TaskRepository repository,
            String taskId,
            TaskState gated,
            PipelineDefinition definition,
            Runnable beforeWrite) {
        // approveGate refuses a position that is not a gate, so the cast below cannot fail.
        TaskState approved = gated.approveGate(definition);
        beforeWrite.run();
        repository.approveCheckpoint(taskId, (Position.AwaitingApproval) gated.position(), approved);
        return approved;
    }

    /**
     * The {@code run --resume} checkpoint continuation, shared by {@link
     * GitResumeContinuation#resumePaused} and {@link ContainerResumeOutcomes#resumePaused}: at a
     * gate — whether or not its park was recorded — the approval commit opens it (FR4); a legacy
     * {@code paused} outcome recorded past its stage, which no gate holds, is consumed by the resumed
     * write with the state kept as recorded (FR7, design D4), as {@code take}'s return of the same
     * tip is. Either way one commit lands before the engine runs.
     *
     * @param beforeWrite as for {@link #approve}; run before either commit
     * @return the state the engine continues from
     */
    static TaskState continuePause(
            TaskRepository repository,
            String taskId,
            TaskState recorded,
            PipelineDefinition definition,
            Runnable beforeWrite) {
        if (recorded.position() instanceof Position.AwaitingApproval) {
            return approve(repository, taskId, recorded, definition, beforeWrite);
        }
        beforeWrite.run();
        repository.resumeFrom(taskId, recorded);
        return recorded;
    }
}
