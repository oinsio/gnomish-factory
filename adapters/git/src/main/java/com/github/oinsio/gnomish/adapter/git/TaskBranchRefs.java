package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;

/**
 * The one spelling of a task branch's two full ref names: its head, {@code
 * refs/heads/gnomish/<sanitized taskId>} — the branch in a clone, and the same name on {@code
 * origin} — and its remote-tracking ref in a clone, {@code
 * refs/remotes/origin/gnomish/<sanitized taskId>}. Both are built over {@link
 * TaskIdSanitizer#branchName}, so a taskId names one branch the same way in every reader and
 * writer of the task branch.
 *
 * <p>Single owner of design D5 of fix-terminal-receipt-convergence: the refs are {@code String}s
 * because the git argv needs them, so the owner is enforced by the prefix gate in {@code
 * ReceiptProbeGateSpec} ({@code :bootstrap}), not by a type — no production source of {@code
 * adapters/git}, {@code application} or {@code bootstrap} spells either prefix literal outside
 * this class, except the base-ref readers the gate lists with their reasons. The {@code
 * gittransfer} leaf's push and register argv spell their own, outside that scan.
 *
 * <p>Implements FR8 of fix-terminal-receipt-convergence.
 */
public final class TaskBranchRefs {

    private static final String HEADS = "refs/heads/";
    private static final String TRACKING = "refs/remotes/origin/";

    /** The namespace of every task branch head, {@code refs/heads/gnomish/}, for an enumeration. */
    public static final String HEAD_NAMESPACE = HEADS + TaskIdSanitizer.BRANCH_PREFIX;

    /** The namespace of every task branch's remote-tracking ref, {@code refs/remotes/origin/gnomish/}. */
    public static final String TRACKING_NAMESPACE = TRACKING + TaskIdSanitizer.BRANCH_PREFIX;

    private TaskBranchRefs() {}

    /**
     * The head of {@code taskId}'s task branch, {@code refs/heads/gnomish/<sanitized taskId>}.
     *
     * @param taskId the tracker's original taskId; sanitized via {@link TaskIdSanitizer#branchName}
     * @return the branch head's full ref name
     * @throws com.github.oinsio.gnomish.app.port.git.InvalidTaskIdException if {@code taskId} cannot be
     *     sanitized into a safe branch name
     */
    public static String head(String taskId) {
        return HEADS + TaskIdSanitizer.branchName(taskId);
    }

    /**
     * The remote-tracking ref of {@code taskId}'s task branch, {@code
     * refs/remotes/origin/gnomish/<sanitized taskId>}.
     *
     * @param taskId the tracker's original taskId; sanitized via {@link TaskIdSanitizer#branchName}
     * @return the remote-tracking ref's full name
     * @throws com.github.oinsio.gnomish.app.port.git.InvalidTaskIdException if {@code taskId} cannot be
     *     sanitized into a safe branch name
     */
    public static String tracking(String taskId) {
        return TRACKING + TaskIdSanitizer.branchName(taskId);
    }
}
