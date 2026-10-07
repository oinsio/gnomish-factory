package com.github.oinsio.gnomish.app.port;

import com.github.oinsio.gnomish.domain.engine.Position;
import java.io.Serial;

/**
 * Thrown by {@link TaskRepository#approveCheckpoint} when the branch tip does not show the gate the
 * caller asked to open — its position is a stage, the pipeline end, or the gate of another stage —
 * or when the state handed in would leave the task at a gate again. Nothing was written: the
 * refusal is decided on what the tip alone shows, before any commit step (design D2, NFR-R2 of
 * make-checkpoint-gate-durable), so a repeated approval on a tip it already moved refuses and
 * changes nothing.
 *
 * <p>The repository reports the refusal once, at WARN with its catalog code, where it decides it;
 * a caller that catches this rethrows or logs at DEBUG (one failure, one log).
 *
 * <p>Implements FR3, NFR-O1, NFR-R2 of make-checkpoint-gate-durable.
 */
public final class CheckpointApprovalRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Not serializable state anyone reads back across a JVM: the refusal is consumed in-process. */
    @SuppressWarnings("serial")
    private final Position actualPosition;

    /**
     * @param taskId the task whose approval was refused; never blank
     * @param gate the gate the caller asked to open; never null
     * @param actualPosition the position the branch tip actually records; never null
     */
    public CheckpointApprovalRefusedException(String taskId, Position.AwaitingApproval gate, Position actualPosition) {
        super("checkpoint approval refused for task \"" + taskId + "\": asked to open the gate after stage \""
                + gate.stage() + "\", but the branch tip is at " + actualPosition);
        this.actualPosition = actualPosition;
    }

    /** The position the branch tip records — what the refusal was decided on. */
    public Position actualPosition() {
        return actualPosition;
    }
}
