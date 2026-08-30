package com.github.oinsio.gnomish.app.port.git;

import java.io.Serial;

/**
 * Thrown when a task branch's local tip and its {@code origin/<branch>} tip have diverged while
 * this instance holds no claim on the task — the one shape the automatic discard of FR8 does not
 * cover, because its whole justification is the claim protocol.
 *
 * <p>FR8 discards the local line automatically and continues from origin, and it is safe to do so
 * <em>under a live claim</em>: origin advances only through legitimate lease holders, so a local
 * commit that never reached origin is not durable for the fleet. On a claimless path — {@code
 * gnomish run --resume} in either execution mode, which has no tracker and no claim at all — that
 * premise does not hold: origin may have been advanced by a human or by another manual run, and
 * the local line is the operator's own, possibly only, copy. Discarding it there would be an
 * automatic loss of work the coordination protocol never arbitrated, so this path fails closed and
 * hands the decision back, exactly as add-git-workflow's FR9 did before FR8 narrowed it to claimed
 * runs.
 *
 * <p>This is an operator-actionable condition, not an internal defect: a human inspects both
 * histories and decides how to reconcile them (rebase, merge, or discard one line explicitly)
 * before the task can resume. It maps to exit code 5.
 *
 * <p>Implements FR8, NFR-R3 of harden-task-branch-contract; supersedes the unconditional FR9,
 * NFR-R3 rule of add-git-workflow.
 */
public final class DivergedBranchException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param taskId the task whose branch diverged
     * @param branchName the task branch name, e.g. {@code gnomish/PROJ-42}
     * @param localTip the local branch tip SHA
     * @param remoteTip the {@code origin/<branch>} remote-tracking tip SHA
     */
    public DivergedBranchException(String taskId, String branchName, String localTip, String remoteTip) {
        super("cannot resume task \"" + taskId + "\": branch \"" + branchName
                + "\" has diverged from origin — local " + localTip + " and origin " + remoteTip
                + " share no ancestry relationship, and this run holds no claim on the task, so the local line"
                + " cannot be discarded automatically; inspect both histories and reconcile manually (e.g. rebase"
                + " or merge) before resuming, or resume through \"gnomish take --resume\", which reconciles under"
                + " its claim");
    }
}
