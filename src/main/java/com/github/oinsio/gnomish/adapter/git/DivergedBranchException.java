package com.github.oinsio.gnomish.adapter.git;

import java.io.Serial;

/**
 * Thrown when {@link WorktreeDivergenceCheck} finds that a task branch's local tip and its
 * {@code origin/<branch>} remote-tracking tip have diverged — neither is an ancestor of the
 * other, so no auto-resolution is possible without a force-push or a merge decision (FR9, design
 * D9: "auto-resolution of two workers = claim protocol = factory loop"). This is an
 * operator-actionable condition, not an internal defect: a human must inspect both histories and
 * decide how to reconcile them (e.g. rebase, merge, or discard one line explicitly) before the
 * task can resume.
 *
 * <p>Distinct from {@link RoundBoundaryViolationException}: that type means a single worktree's
 * own history broke an in-round protocol invariant; this one means two independent histories
 * (local vs. origin) cannot be reconciled without human judgment.
 *
 * <p>Implements FR9, NFR-R3 of add-git-workflow.
 */
public final class DivergedBranchException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param taskId the task whose branch diverged
     * @param branchName the task branch name, e.g. {@code gnomish/PROJ-42}
     * @param localTip the worktree's local branch tip SHA
     * @param remoteTip the {@code origin/<branch>} remote-tracking tip SHA
     */
    public DivergedBranchException(String taskId, String branchName, String localTip, String remoteTip) {
        super("cannot resume task \"" + taskId + "\": branch \"" + branchName
                + "\" has diverged from origin — local " + localTip + " and origin " + remoteTip
                + " share no ancestry relationship; inspect both histories and reconcile manually"
                + " (e.g. rebase or merge) before resuming");
    }
}
