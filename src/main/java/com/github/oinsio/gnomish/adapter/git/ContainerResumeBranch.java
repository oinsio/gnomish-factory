package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locates and reconciles the local task branch for a container-mode resume
 * (FR6, git-task-persistence "Resume from the recorded branch"): branch lookup
 * follows the shared {@link TaskBranchLocator} order — local → remote-tracking
 * → narrow fetch of exactly {@code gnomish/<task>} — and, because container
 * mode has no worktree, reconciliation happens on refs alone: a
 * remote-tracking-only branch becomes a local branch ref (no checkout); a
 * local branch behind origin is fast-forwarded; ahead is kept; diverged throws
 * {@link DivergedBranchException} — never a force update.
 *
 * <p>Implements FR6, FR17 of add-sandbox-core.
 */
public final class ContainerResumeBranch {

    private static final Logger log = LoggerFactory.getLogger(ContainerResumeBranch.class);

    private final GitProcessRunner runner;

    public ContainerResumeBranch(GitProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Ensures a reconciled local branch for {@code taskId} exists in {@code cloneDir}.
     *
     * @return true when the branch exists locally after this call; false when it exists nowhere
     * @throws DivergedBranchException if local and origin tips share no ancestry relationship
     */
    public boolean ensureLocalBranch(Path cloneDir, String taskId) {
        String branch = TaskIdSanitizer.branchName(taskId);
        return switch (new TaskBranchLocator(runner).locate(cloneDir, taskId)) {
            case BranchLocation.Local ignored -> {
                reconcile(cloneDir, taskId, branch);
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
        };
    }

    /**
     * Ref-level divergence reconciliation (FR9 of add-git-workflow, containerized): equal or
     * ahead keep the local tip; behind fast-forwards the local ref from origin; diverged throws.
     * With no remote-tracking ref there is nothing to reconcile against.
     */
    private void reconcile(Path cloneDir, String taskId, String branch) {
        String local = "refs/heads/" + branch;
        String tracking = "refs/remotes/origin/" + branch;
        if (runner.run(cloneDir, "rev-parse", "--verify", "--quiet", tracking).exitCode() != 0) {
            return;
        }
        if (isAncestor(cloneDir, tracking, local)) {
            return;
        }
        if (isAncestor(cloneDir, local, tracking)) {
            log.info("local {} is behind origin; fast-forwarding the ref", branch);
            GitCommandResult ff = runner.run(cloneDir, "fetch", "origin", branch + ":" + branch);
            if (ff.exitCode() != 0) {
                throw new IllegalStateException("could not fast-forward " + branch + ": " + ff.stderr());
            }
            return;
        }
        throw new DivergedBranchException(taskId, branch, tip(cloneDir, local), tip(cloneDir, tracking));
    }

    private String tip(Path cloneDir, String ref) {
        return runner.run(cloneDir, "rev-parse", ref).stdout().trim();
    }

    private boolean isAncestor(Path cloneDir, String maybeAncestor, String ref) {
        return runner.run(cloneDir, "merge-base", "--is-ancestor", maybeAncestor, ref)
                        .exitCode()
                == 0;
    }
}
