package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.BranchAdoption;
import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import java.nio.file.Path;

/**
 * Locates and reconciles the local task branch for a container-mode resume
 * (FR6, git-task-persistence "Resume from the recorded branch"): branch lookup
 * follows the shared {@link TaskBranchLocator} order — local → remote-tracking
 * → narrow fetch of exactly {@code gnomish/<task>} — and, because container
 * mode has no worktree, reconciliation is the shared {@link
 * ReplicaPairReconciler} in its refs-only mode: a remote-tracking-only branch
 * is adopted by {@link TaskBranchAdoption}, the one owner of that step (design
 * D11 of fix-terminal-receipt-convergence), and an existing local branch is
 * kept, fast-forwarded, or discarded under the claim by the one policy host
 * mode also runs (FR8 of harden-task-branch-contract). The container-local
 * copy of that policy is gone: two implementations of one relation is how the
 * two modes' answers drifted apart.
 *
 * <p>Implements FR6, FR17 of add-sandbox-core; FR8 of harden-task-branch-contract;
 * FR8 of fix-terminal-receipt-convergence.
 */
// Not a record: this is a behavior-bearing reader over the git seam (a collaborator, not immutable
// data), kept as a plain final class for parity with its siblings in this package.
@SuppressWarnings("ClassCanBeRecord")
public final class ContainerResumeBranch {

    private final GitRunner runner;
    private final ClaimEpochSource epochs;
    private final TaskBranchLocator locator;
    private final TaskBranchAdoption adoption;

    /**
     * @param runner the shared git subprocess runner; never null
     * @param epochs the tenure the reconciler's automatic discard is gated on (FR8 of
     *     harden-task-branch-contract); {@link ClaimEpochSource#NONE} on the claimless {@code run
     *     --resume} path, where a diverged branch stops the run instead of discarding the local line
     * @param retry the infrastructure budget the branch lookup is re-attempted under (FR18 of
     *     supervise-daemon-loops-and-embed-dashboard); never null
     */
    public ContainerResumeBranch(GitRunner runner, ClaimEpochSource epochs, GitInfrastructureRetry retry) {
        this.runner = runner;
        this.epochs = epochs;
        this.locator = new TaskBranchLocator(runner, retry);
        this.adoption = new TaskBranchAdoption(runner, locator);
    }

    /**
     * Ensures a reconciled local branch for {@code taskId} exists in {@code cloneDir}: a local
     * branch is reconciled with origin's, a remote-tracking-only one is adopted by the one owner,
     * {@link TaskBranchAdoption} (design D9, D11 of fix-terminal-receipt-convergence).
     *
     * @return adopted, already local (reconciled), or nowhere
     * @throws com.github.oinsio.gnomish.app.port.git.DivergedBranchException when the pair diverged
     *     and this instance holds no tenure on the task (FR8 of harden-task-branch-contract)
     */
    public BranchAdoption ensureLocalBranch(Path cloneDir, String taskId) {
        BranchLocation location = locator.locate(cloneDir, taskId);
        if (location instanceof BranchLocation.Local) {
            ReplicaPairReconciler.forClone(runner, cloneDir, epochs)
                    .reconcile(taskId, TaskIdSanitizer.branchName(taskId));
            return new BranchAdoption.AlreadyLocal(taskId);
        }
        // Unestablished is not absent: the owner throws on it, so the container resume aborts and
        // the claim goes back to the pool rather than forking a duplicate branch (FR6).
        return adoption.adopt(cloneDir, taskId, location);
    }
}
