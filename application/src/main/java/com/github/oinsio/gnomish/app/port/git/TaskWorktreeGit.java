package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import java.nio.file.Path;

/**
 * The worktree-level git capabilities a use case needs: materialize a task's worktree, reconcile it
 * against what the remote holds, salvage uncommitted leftovers, and clean up when a task reaches a
 * terminal outcome.
 *
 * <p>An {@code application}-owned port (FR12b, design D12 of split-into-modules) — see {@link
 * TaskBranchGit} for why these capabilities are expressed as ports rather than constructed
 * in-place. "Worktree" here means the isolated working copy a task is executed in, whatever the
 * backend calls it.
 *
 * <p>Implements FR9, FR10, FR15, NFR-R3 of add-git-workflow; FR12b of split-into-modules.
 */
public interface TaskWorktreeGit {

    /**
     * Ensures {@code taskId}'s worktree exists for {@code branchName}, creating it if absent.
     *
     * @param cloneDir the clone the worktree is linked to; never null
     * @param worktreesRoot the root the task's worktree is materialized under; never null
     * @param taskId the tracker's original taskId; never null
     * @param branchName the task branch to check out; never null
     * @return the worktree's path; never null
     */
    Path ensureWorktree(Path cloneDir, Path worktreesRoot, String taskId, String branchName);

    /**
     * Reconciles a task's local branch against its remote-tracking counterpart.
     *
     * @param worktreeRoot the worktree to reconcile; never null
     * @param taskId the tracker's original taskId; never null
     * @param branchName the task branch name; never null
     * @return how local and remote relate; never null
     */
    DivergenceOutcome reconcile(Path worktreeRoot, String taskId, String branchName);

    /**
     * The salvage handle for {@code worktreeRoot}: commits uncommitted leftovers so a resumed
     * visit starts from a clean tree.
     *
     * @param worktreeRoot the worktree to salvage; never null
     * @return the salvage handle; never null
     */
    WorktreeSalvager salvage(Path worktreeRoot);

    /**
     * Disposes of a task's worktree as {@code outcome} requires.
     *
     * @param cloneDir the clone the worktree is linked to; never null
     * @param worktreePath the worktree to clean up; never null
     * @param outcome the terminal outcome that ended the run; never null
     */
    void cleanUp(Path cloneDir, Path worktreePath, TaskOutcome outcome);

    /**
     * The dispose-shaped seam the {@code serve} worktree janitor hands an eligible environment's
     * key to (FR14, design D10 of add-factory-serve): one bound disposer for the given clone and
     * worktrees root, keyed by the sanitized directory name the janitor found while scanning.
     *
     * @param cloneDir the clone that owns the worktree registrations; never null
     * @param worktreesRoot the root the per-task worktrees live under; never null
     * @return the disposer; never null
     */
    TaskEnvironmentDisposal environmentDisposal(Path cloneDir, Path worktreesRoot);

    /**
     * Prunes worktree registrations whose directories no longer exist.
     *
     * @param cloneDir the clone to prune; never null
     */
    void pruneWorktrees(Path cloneDir);
}
