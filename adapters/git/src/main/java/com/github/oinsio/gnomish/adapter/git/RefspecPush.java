package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.gittransfer.GitPush;
import java.nio.file.Path;

/**
 * The mid-round push (design D2 of fix-lifecycle-push; design D13 of tighten-spec-contract): the
 * task branch alone, {@code git push origin refs/heads/<branch>:refs/heads/<branch>} as the transfer
 * owner builds it ({@link GitPush#branchOnly}) — the exact refspec, never {@code --force} /
 * {@code --force-with-lease}, so a non-fast-forward rejection is just a failed push (NFR-S1). The
 * factory register does not move with it: only a closing push ({@link ClosingPush}) carries the
 * register. Its two callers are the two mid-round push points, the host poll ({@link
 * BestEffortPush#midRound}, from {@link MidRoundPushListener}) and the container harvest ({@link
 * BranchPush}, from {@link MidRoundHarvestListener}).
 *
 * <p>Pure primitive: no precondition, no policy, no logging. The command's raw result is handed
 * back to the caller, which owns what a failure means.
 *
 * <p>Implements FR1, NFR-S1 of fix-lifecycle-push; FR13 of tighten-spec-contract.
 */
// Not a record: this is a behavior-bearing command seam (a collaborator, not immutable data),
// kept as a plain final class for parity with its siblings in this package.
@SuppressWarnings("ClassCanBeRecord")
final class RefspecPush {

    private final GitRunner runner;

    RefspecPush(GitRunner runner) {
        this.runner = runner;
    }

    /**
     * Pushes {@code branch} alone to {@code origin} from {@code repo} under the exact refspec
     * {@code refs/heads/branch:refs/heads/branch}.
     *
     * @param repo the clone or worktree the push runs from; never null
     * @param branch the task branch name; never blank
     * @return the raw command result — a non-zero exit code is a normal outcome here
     */
    GitCommandResult push(Path repo, String branch) {
        return runner.run(repo, GitPush.branchOnly(branch));
    }
}
