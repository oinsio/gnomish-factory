package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.DivergenceOutcome;
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit;
import com.github.oinsio.gnomish.app.port.git.WorktreeSalvager;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import java.nio.file.Path;

/**
 * The git-subprocess implementation of {@link TaskWorktreeGit} (FR12b, design D12 of
 * split-into-modules) — the worktree-side counterpart of {@link GitTaskBranches}, and like it a
 * delegation-only facade over this package's existing collaborators, all sharing one {@link
 * GitProcessRunner}.
 *
 * <p>{@link TaskWorktreeManager} and {@link ReplicaPairReconciler} take their root as a
 * constructor argument, so this facade builds one per call rather than holding a field: both are
 * cheap, stateless wrappers over the shared runner, and hoisting the root into the method
 * signature is what lets a single bound instance serve every concurrent slot.
 *
 * <p>Implements FR9, FR10, FR15, NFR-R3 of add-git-workflow; FR12b of split-into-modules.
 */
public final class GitTaskWorktrees implements TaskWorktreeGit {

    private final GitProcessRunner runner;
    private final TaskWorktreeCleanup cleanup;
    private final ClaimEpochSource epochs;

    /**
     * @param runner the git subprocess runner shared across this facade's collaborators; never null
     * @param epochs the tenure a salvage commit is stamped with (FR13 of
     *     harden-task-branch-contract), and the one the reconciler's automatic discard is gated on
     *     (FR8); {@link ClaimEpochSource#NONE} where no claim is held
     */
    public GitTaskWorktrees(GitProcessRunner runner, ClaimEpochSource epochs) {
        this.runner = runner;
        this.cleanup = new TaskWorktreeCleanup(runner);
        this.epochs = epochs;
    }

    @Override
    public Path ensureWorktree(Path cloneDir, Path worktreesRoot, String taskId, String branchName) {
        return new TaskWorktreeManager(runner, worktreesRoot).ensureWorktree(cloneDir, taskId, branchName);
    }

    @Override
    public DivergenceOutcome reconcile(Path worktreeRoot, String taskId, String branchName) {
        return ReplicaPairReconciler.forWorktree(runner, worktreeRoot, epochs).reconcile(taskId, branchName);
    }

    @Override
    public WorktreeSalvager salvage(Path worktreeRoot) {
        return new WorktreeSalvage(runner, worktreeRoot, epochs);
    }

    @Override
    public void cleanUp(Path cloneDir, Path worktreePath, TaskOutcome outcome) {
        cleanup.cleanUp(cloneDir, worktreePath, outcome);
    }

    @Override
    public TaskEnvironmentDisposal environmentDisposal(Path cloneDir, Path worktreesRoot) {
        return new WorktreeEnvironmentDisposal(runner, cloneDir, worktreesRoot);
    }

    @Override
    public void pruneWorktrees(Path cloneDir) {
        cleanup.pruneWorktrees(cloneDir);
    }
}
