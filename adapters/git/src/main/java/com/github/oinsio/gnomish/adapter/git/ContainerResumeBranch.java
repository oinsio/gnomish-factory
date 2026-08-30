package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import java.nio.file.Path;

/**
 * Locates and reconciles the local task branch for a container-mode resume
 * (FR6, git-task-persistence "Resume from the recorded branch"): branch lookup
 * follows the shared {@link TaskBranchLocator} order — local → remote-tracking
 * → narrow fetch of exactly {@code gnomish/<task>} — and, because container
 * mode has no worktree, reconciliation is the shared {@link
 * ReplicaPairReconciler} in its refs-only mode: a remote-tracking-only branch
 * becomes a local branch ref (no checkout), and an existing local branch is
 * kept, fast-forwarded, or discarded under the claim by the one policy host
 * mode also runs (FR8 of harden-task-branch-contract). The container-local
 * copy of that policy is gone: two implementations of one relation is how the
 * two modes' answers drifted apart.
 *
 * <p>Implements FR6, FR17 of add-sandbox-core; FR8 of harden-task-branch-contract.
 */
// Not a record: this is a behavior-bearing reader over the git seam (a collaborator, not immutable
// data), kept as a plain final class for parity with its siblings in this package.
@SuppressWarnings("ClassCanBeRecord")
public final class ContainerResumeBranch {

    private final GitProcessRunner runner;
    private final ClaimEpochSource epochs;

    /**
     * @param runner the shared git subprocess runner; never null
     * @param epochs the tenure the reconciler's automatic discard is gated on (FR8 of
     *     harden-task-branch-contract); {@link ClaimEpochSource#NONE} on the claimless {@code run
     *     --resume} path, where a diverged branch stops the run instead of discarding the local line
     */
    public ContainerResumeBranch(GitProcessRunner runner, ClaimEpochSource epochs) {
        this.runner = runner;
        this.epochs = epochs;
    }

    /**
     * Ensures a reconciled local branch for {@code taskId} exists in {@code cloneDir}.
     *
     * @return true when the branch exists locally after this call; false when it exists nowhere
     * @throws com.github.oinsio.gnomish.app.port.git.DivergedBranchException when the pair diverged
     *     and this instance holds no tenure on the task (FR8 of harden-task-branch-contract)
     */
    public boolean ensureLocalBranch(Path cloneDir, String taskId) {
        String branch = TaskIdSanitizer.branchName(taskId);
        return switch (new TaskBranchLocator(runner).locate(cloneDir, taskId)) {
            case BranchLocation.Local ignored -> {
                ReplicaPairReconciler.forClone(runner, cloneDir, epochs).reconcile(taskId, branch);
                yield true;
            }
            case BranchLocation.RemoteTracking(String trackingRef) -> {
                GitCommandResult create = runner.run(cloneDir, "branch", branch, trackingRef);
                if (create.exitCode() != 0) {
                    throw new IllegalStateException("could not create local branch " + branch + " from " + trackingRef
                            + ": " + create.stderr());
                }
                yield true;
            }
            case BranchLocation.NotFound ignored -> false;
            // Unestablished is not absent: routing this to "create the branch" is the duplicate
            // fork FR6 removes, so the container resume aborts and the claim goes back to the pool.
            case BranchLocation.Unavailable(String reason) ->
                throw new BranchLocationUnavailableException(taskId, reason);
        };
    }
}
