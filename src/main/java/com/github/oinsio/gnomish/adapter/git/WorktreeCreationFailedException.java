package com.github.oinsio.gnomish.adapter.git;

import java.io.Serial;

/**
 * Thrown when {@code git worktree add} fails for a task whose worktree was not already
 * registered at the deterministic path — e.g. the target branch is already checked out in
 * another worktree elsewhere, or the worktree directory is not empty. Not expected in normal
 * create-or-reuse operation, since {@link TaskWorktreeManager} checks for an existing, registered
 * worktree at the deterministic path first; surfaced as an exception rather than a result type
 * because, unlike {@link BranchCreationResult}'s outcomes, this is not a decidable, everyday
 * caller branch — it signals an unexpected git-level conflict.
 *
 * <p>Implements FR6, FR8 of add-git-workflow (design D6).
 */
public final class WorktreeCreationFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param taskId the taskId the worktree was being created for
     * @param branchName the branch {@code git worktree add} was asked to check out
     * @param gitStderr the failed git command's captured stderr
     */
    public WorktreeCreationFailedException(String taskId, String branchName, String gitStderr) {
        super("failed to create worktree for taskId \"" + taskId + "\" (branch " + branchName + "): " + gitStderr);
    }
}
