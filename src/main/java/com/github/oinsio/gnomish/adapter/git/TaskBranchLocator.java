package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;

/**
 * Locates the task branch {@code gnomish/<taskId>} in a clone, trying — in order, stopping at the
 * first hit — a local branch, an already-present remote-tracking ref, then a narrow fetch of
 * exactly that one ref from {@code origin}. Shared verbatim by two very different callers: resume
 * (task 4.6), which goes on to materialize a worktree from whatever ref this returns, and
 * inspection (`status`/`usage`, task 5.2), which only ever reads via {@code git show <ref>:<path>}
 * and must never create a local branch or touch the working copy itself — this locator never
 * checks out anything, so both callers get the same read-only guarantee for free.
 *
 * <p>The narrow fetch uses {@code git fetch origin <branch>:refs/remotes/origin/<branch>} — an
 * explicit source:destination refspec naming exactly one branch, never {@code --all} or a
 * wildcard — which both retrieves the one ref needed and leaves a proper {@code
 * refs/remotes/origin/...} tracking ref behind, verified empirically to be readable by both {@code
 * git show} and usable as a {@code git worktree add} start point. This satisfies FR8's "never
 * fetching anything else".
 *
 * <p>Implements FR8, FR13 of add-git-workflow.
 */
public final class TaskBranchLocator {

    private final GitProcessRunner runner;

    public TaskBranchLocator(GitProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Locates the task branch for {@code taskId} in the clone at {@code cloneDir}.
     *
     * @param cloneDir the working directory of an existing git clone (the {@code --dir} target)
     * @param taskId the tracker's original taskId; sanitized via {@link
     *     TaskIdSanitizer#branchName}
     * @return where the branch was found — local, remote-tracking (already present or
     *     just-fetched), or not found anywhere, even after the narrow fetch attempt
     * @throws InvalidTaskIdException if {@code taskId} cannot be sanitized into a safe branch name
     */
    public BranchLocation locate(Path cloneDir, String taskId) {
        String branchName = TaskIdSanitizer.branchName(taskId);
        String localRef = "refs/heads/" + branchName;
        String trackingRef = "refs/remotes/origin/" + branchName;

        if (refExists(cloneDir, localRef)) {
            return new BranchLocation.Local(localRef);
        }
        if (refExists(cloneDir, trackingRef)) {
            return new BranchLocation.RemoteTracking(trackingRef);
        }

        GitCommandResult fetch = runner.run(cloneDir, "fetch", "origin", branchName + ":" + trackingRef);
        if (fetch.exitCode() == 0 && refExists(cloneDir, trackingRef)) {
            return new BranchLocation.RemoteTracking(trackingRef);
        }

        return new BranchLocation.NotFound();
    }

    private boolean refExists(Path cloneDir, String ref) {
        return runner.run(cloneDir, "rev-parse", "--verify", "--quiet", ref).exitCode() == 0;
    }
}
