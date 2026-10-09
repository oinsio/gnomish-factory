package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.CheckpointApprovalRefusedException;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The approval write's refusal rule and its two log lines, owned once for both media (FR3, NFR-O1
 * of make-checkpoint-gate-durable, design D2): {@link GitTaskRepository} and {@link
 * GitObjectsTaskRepository} read their tip each by its own mechanism and hand the recorded
 * position here, so the two cannot refuse on different tip conditions, and the refusal is one
 * catalogued WARN however many media emit it.
 *
 * <p>The rule is decided on what the tip alone shows — the repositories hold no pipeline
 * definition: the tip must be at exactly the gate the caller named, and the state the caller
 * computed through {@code TaskState.approveGate} must not be at a gate itself. A position the
 * caller read from an earlier tip therefore never opens a gate the task has since reached, and an
 * approval repeated over a tip it already moved refuses rather than writing twice (NFR-R2).
 *
 * <p>Implements FR3, NFR-O1, NFR-R2 of make-checkpoint-gate-durable.
 */
final class CheckpointApprovalCheck {

    private static final Logger log = LoggerFactory.getLogger(CheckpointApprovalCheck.class);

    private CheckpointApprovalCheck() {}

    /**
     * Refuses, before any commit step, an approval the tip does not admit.
     *
     * @param taskId the task being approved; for the refusal and its line
     * @param gate the gate the caller asks to open
     * @param tipPosition the position the branch tip records
     * @param approved the state the approval would write
     * @throws CheckpointApprovalRefusedException when the tip is not at {@code gate} or {@code
     *     approved} is at a gate
     */
    static void requireAdmitted(
            String taskId, Position.AwaitingApproval gate, Position tipPosition, TaskState approved) {
        if (tipPosition.equals(gate) && !(approved.position() instanceof Position.AwaitingApproval)) {
            return;
        }
        log.warn(
                OperatorEvent.CHECKPOINT_APPROVAL_REFUSED.head()
                        + "checkpoint approval refused for task {}: asked to open the gate after stage '{}' into"
                        + " {}, but the branch tip is at {}; nothing was written",
                taskId,
                gate.stage(),
                approved.position(),
                tipPosition);
        throw new CheckpointApprovalRefusedException(taskId, gate, tipPosition);
    }

    /**
     * The approval's INFO line, after its commit landed (NFR-O1): the task, the stage whose gate
     * opened and where the task now stands.
     *
     * @param taskId the approved task
     * @param gate the gate that opened
     * @param approved the state the approval committed
     */
    static void approved(String taskId, Position.AwaitingApproval gate, TaskState approved) {
        log.info(
                "checkpoint approved for task {}: the gate after stage '{}' is open, position now {}",
                taskId,
                gate.stage(),
                approved.position());
    }
}
