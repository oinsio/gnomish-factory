package com.github.oinsio.gnomish.app.branch;

import com.github.oinsio.gnomish.domain.branch.BranchShape;
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one line a repair leaves behind (NFR-O1): whenever a pickup classifies a branch to anything
 * other than the shape a healthy progression expects, it says so once — naming the task, the shape,
 * the claim epoch, and what it did about it — so a fleet's repairs are readable without turning on
 * debug logging.
 *
 * <p>A repair of a task that has already been repaired is the interesting case, so it is raised to
 * WARN. "Already" is judged against the task's own persisted recovery-attempt accounting, not a
 * clock this class keeps: a repair arriving while that counter already records a prior one is a
 * repeat. Callers pass the counter they read; the shared accounting itself lands with the recovery
 * budget.
 *
 * <p>Implements NFR-O1 of harden-task-branch-contract.
 */
public final class BranchRepairLog {

    private static final Logger log = LoggerFactory.getLogger(BranchRepairLog.class);

    /**
     * Records one classification. A clean shape logs nothing — a healthy pickup is not a repair,
     * and a line per pickup would bury the ones that matter.
     *
     * @param taskId the task whose branch was classified; never blank
     * @param shape the classifier's verdict
     * @param epoch the claim epoch the repair runs under, or {@code null} when the reader holds no
     *     claim
     * @param action what the recovery owner did about it, in plain words, e.g. {@code "resuming at
     *     the recorded position"}
     * @param priorRecoveryAttempts recovery attempts this task's persisted accounting already
     *     records; anything above zero makes this a repeat
     */
    public void classified(
            String taskId, BranchShape shape, @Nullable ClaimEpoch epoch, String action, int priorRecoveryAttempts) {
        if (shape.isClean()) {
            return;
        }
        if (priorRecoveryAttempts > 0) {
            log.warn(
                    OperatorEvent.BRANCH_REPAIR_REPEATED.head()
                            + "branch repair: task={} shape={} epoch={} owner={} action={} priorAttempts={} (repeated)",
                    taskId,
                    describe(shape),
                    render(epoch),
                    shape.recoveryOwner(),
                    action,
                    priorRecoveryAttempts);
            return;
        }
        log.info(
                "branch repair: task={} shape={} epoch={} owner={} action={}",
                taskId,
                describe(shape),
                render(epoch),
                shape.recoveryOwner(),
                action);
    }

    /**
     * The shape's name, plus the diagnosis the three quarantining shapes carry. Exhaustive by
     * construction, with no default branch: a shape added to the closed set has to be named here
     * too (FR2).
     */
    private static String describe(BranchShape shape) {
        String name = shape.getClass().getSimpleName();
        return switch (shape) {
            case BranchShape.Corrupt(String reason) -> name + "(" + reason + ")";
            case BranchShape.Unknown(String reason) -> name + "(" + reason + ")";
            case BranchShape.UnsupportedVersion(String file, int observed, int supported) ->
                name + "(" + file + ": " + observed + ", supported " + supported + ")";
            case BranchShape.Bare(),
                    BranchShape.Created(),
                    BranchShape.InProgress(),
                    BranchShape.Parked(),
                    BranchShape.Answered(),
                    BranchShape.CompletedUncleaned(),
                    BranchShape.Delivered(),
                    BranchShape.StaleEpoch() -> name;
        };
    }

    private static String render(@Nullable ClaimEpoch epoch) {
        return epoch == null ? "none" : Long.toString(epoch.token());
    }
}
