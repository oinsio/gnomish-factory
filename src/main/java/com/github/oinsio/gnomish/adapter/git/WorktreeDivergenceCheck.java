package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;

/**
 * Reconciles a resumed task worktree's local branch tip against its {@code origin/<branch>}
 * remote-tracking ref before the engine loop resumes (FR9, design D9): equal → nothing to do;
 * behind → fast-forward the worktree and discard any uncommitted leftovers (they belong to an
 * outdated history line); ahead → leave the worktree untouched, a later best-effort push catches
 * origin up (FR11); diverged → throw {@link DivergedBranchException} rather than guess, since
 * auto-resolving two workers' histories is the claim protocol's job, not this check's (NFR-R3).
 *
 * <p>{@link #reconcile} always attempts its own narrow fetch of {@code branchName} first (mirroring
 * {@link TaskBranchLocator}'s fetch, an explicit source:destination refspec naming exactly one
 * branch), since {@link TaskBranchLocator#locate} does not fetch when the branch is already found
 * locally — the common resume case for the instance that originally created the task — and a stale
 * or absent {@code origin/<branch>} ref would otherwise hide a peer's already-pushed work (NFR-R3:
 * "resume semantics rely only on what reached origin"). The fetch is best-effort: no remote
 * configured, the branch never pushed, or a network failure all fall back silently to whatever
 * tracking ref (if any) is already present, since the point of {@link
 * DivergenceOutcome#NO_REMOTE_TRACKING_REF} is precisely "nothing to reconcile", not "the fetch
 * itself must succeed".
 *
 * <p>The fast-forward and discard ({@code reset --hard} + {@code clean -fd}) run with {@code
 * worktreeRoot} as {@code cwd}, never touching the owning clone's own checked-out branch or
 * working tree (FR7).
 *
 * <p>Implements FR9, NFR-R3 of add-git-workflow.
 */
public final class WorktreeDivergenceCheck {

    private final GitProcessRunner runner;
    private final Path worktreeRoot;

    public WorktreeDivergenceCheck(GitProcessRunner runner, Path worktreeRoot) {
        this.runner = runner;
        this.worktreeRoot = worktreeRoot;
    }

    /**
     * Classifies and, for the {@code behind} case, immediately reconciles the divergence between
     * {@code branchName}'s local tip and {@code origin/<branchName>} — see class javadoc for the
     * per-outcome handling.
     *
     * @param taskId the task the branch belongs to, for the diverged-case error message
     * @param branchName the task branch name, e.g. {@code gnomish/PROJ-42}
     * @return the classified outcome (already reconciled if {@code BEHIND})
     * @throws DivergedBranchException if local and origin share no ancestry relationship
     */
    public DivergenceOutcome reconcile(String taskId, String branchName) {
        String trackingRef = "refs/remotes/origin/" + branchName;
        runner.run(worktreeRoot, "fetch", "origin", branchName + ":" + trackingRef);
        if (!refExists(trackingRef)) {
            return DivergenceOutcome.NO_REMOTE_TRACKING_REF;
        }

        String localTip = revParse("HEAD");
        String remoteTip = revParse(trackingRef);
        if (localTip.equals(remoteTip)) {
            return DivergenceOutcome.EQUAL;
        }
        if (isAncestor(localTip, remoteTip)) {
            fastForwardAndDiscard(remoteTip);
            return DivergenceOutcome.BEHIND;
        }
        if (isAncestor(remoteTip, localTip)) {
            return DivergenceOutcome.AHEAD;
        }
        throw new DivergedBranchException(taskId, branchName, localTip, remoteTip);
    }

    private void fastForwardAndDiscard(String remoteTip) {
        runner.run(worktreeRoot, "reset", "--hard", remoteTip);
        runner.run(worktreeRoot, "clean", "-fd");
    }

    private boolean isAncestor(String ancestor, String descendant) {
        return runner.run(worktreeRoot, "merge-base", "--is-ancestor", ancestor, descendant)
                        .exitCode()
                == 0;
    }

    private boolean refExists(String ref) {
        return runner.run(worktreeRoot, "rev-parse", "--verify", "--quiet", ref).exitCode() == 0;
    }

    private String revParse(String ref) {
        return runner.run(worktreeRoot, "rev-parse", ref).stdout().trim();
    }
}
