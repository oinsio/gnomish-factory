package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.RemoteBranchTip.Carriage;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.InvalidTaskIdException;
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
 * <p>A fetch that does not produce the ref is <em>not</em> absence (FR6 of
 * harden-task-branch-contract): it is a question this clone cannot answer, and only {@code origin}
 * can. So the failure path asks it — one {@code ls-remote} through {@link RemoteBranchTip}, whose
 * three-way answer is the classification: origin answered and holds no such ref → {@link
 * BranchLocation.NotFound}; origin holds it, or never answered at all → {@link
 * BranchLocation.Unavailable}, retried under {@link GitInfrastructureRetry} and, if it never
 * settles, left for the caller to abort on. Treating every failed fetch as absence is what forked
 * a duplicate branch for a task that already had one, and it is the status quo this replaces. With
 * no {@code origin} configured there is no one to ask and the clone's own refs are the whole truth,
 * so the local-only run keeps answering {@link BranchLocation.NotFound} exactly as before.
 *
 * <p>Implements FR8, FR13 of add-git-workflow; FR6 of harden-task-branch-contract.
 */
public final class TaskBranchLocator {

    private final GitProcessRunner runner;
    private final OriginRemote origin;
    private final RemoteBranchTip remoteTip;
    private final GitInfrastructureRetry retry;

    public TaskBranchLocator(GitProcessRunner runner) {
        this(runner, GitInfrastructureRetry.system());
    }

    /**
     * @param runner the git subprocess seam; never null
     * @param retry the infrastructure budget the unsettled lookup is re-attempted under; never null
     */
    public TaskBranchLocator(GitProcessRunner runner, GitInfrastructureRetry retry) {
        this.runner = runner;
        this.origin = new OriginRemote(runner);
        this.remoteTip = new RemoteBranchTip(runner);
        this.retry = retry;
    }

    /**
     * Locates the task branch for {@code taskId} in the clone at {@code cloneDir}.
     *
     * @param cloneDir the working directory of an existing git clone (the {@code --dir} target)
     * @param taskId the tracker's original taskId; sanitized via {@link
     *     TaskIdSanitizer#branchName}
     * @return where the branch was found — local, remote-tracking (already present or
     *     just-fetched), confirmed missing everywhere, or unestablished because origin could not
     *     be asked
     * @throws InvalidTaskIdException if {@code taskId} cannot be sanitized into a safe branch name
     */
    public BranchLocation locate(Path cloneDir, String taskId) {
        String branchName = TaskIdSanitizer.branchName(taskId);
        return retry.until(
                () -> attempt(cloneDir, branchName), located -> !(located instanceof BranchLocation.Unavailable));
    }

    private BranchLocation attempt(Path cloneDir, String branchName) {
        String localRef = "refs/heads/" + branchName;
        String trackingRef = "refs/remotes/origin/" + branchName;

        if (refExists(cloneDir, localRef)) {
            return new BranchLocation.Local(localRef);
        }
        if (refExists(cloneDir, trackingRef)) {
            return new BranchLocation.RemoteTracking(trackingRef);
        }

        GitCommandResult fetch = runner.run(cloneDir, "fetch", "origin", branchName + ":" + trackingRef);
        // The ref is the authority, not the fetch's exit code: a fetch killed on its deadline or
        // cut short by a shutdown cannot have created the tracking ref, and a fetch that reports
        // success without one has delivered nothing. Reading the ref answers all of those at once.
        if (refExists(cloneDir, trackingRef)) {
            return new BranchLocation.RemoteTracking(trackingRef);
        }
        // No origin means no one to ask: whatever this clone holds is the whole truth, and a
        // purely local run must keep routing fresh rather than aborting forever (UX3).
        if (!origin.isConfigured(cloneDir)) {
            return new BranchLocation.NotFound();
        }
        return classifyFailedFetch(cloneDir, branchName, fetch);
    }

    private BranchLocation classifyFailedFetch(Path cloneDir, String branchName, GitCommandResult fetch) {
        return switch (remoteTip.confirmBranch(cloneDir, branchName)) {
            case Carriage.ABSENT -> new BranchLocation.NotFound();
            case Carriage.CARRIES ->
                new BranchLocation.Unavailable("origin carries " + branchName
                        + " but the narrow fetch did not deliver it (" + why(fetch) + ")");
            case Carriage.UNKNOWN ->
                new BranchLocation.Unavailable(
                        "origin did not answer whether " + branchName + " exists (" + why(fetch) + ")");
        };
    }

    private static String why(GitCommandResult fetch) {
        return switch (fetch.termination()) {
            case TIMED_OUT -> "the fetch timed out";
            case INTERRUPTED -> "the fetch was interrupted";
            case EXITED ->
                "the fetch exited " + fetch.exitCode() + ": " + fetch.stderr().trim();
        };
    }

    private boolean refExists(Path cloneDir, String ref) {
        return runner.run(cloneDir, "rev-parse", "--verify", "--quiet", ref).exitCode() == 0;
    }
}
