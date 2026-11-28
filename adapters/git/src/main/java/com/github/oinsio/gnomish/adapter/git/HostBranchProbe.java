package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import java.nio.file.Path;

/**
 * The host worktree's two branch facts and its tip read: is {@code HEAD} on the task branch, does
 * it still descend from a given tip, and what {@code HEAD} is. The round close ({@link
 * GitAttemptPersistence}) asks the first as part of its boundary — a worktree off the task branch is
 * a violation — and the best-effort pushes ({@link BestEffortPush}, {@link MidRoundPushListener})
 * ask both as their skip-with-WARN precondition, through the same git invocations rather than
 * copies of them. The committed range itself is judged by {@link GnomeRangeInspection}.
 *
 * <p>The tip read here is the raw result the read-only mid-round poll classifies for itself ({@link
 * #readHead()}, FR13 of harden-logging-observability); the durable-baseline reading that refuses on
 * a failed resolution is the round's opening, {@link RoundTokenMint}.
 *
 * <p>Implements FR12, NFR-S1 of add-git-workflow; FR13 of harden-logging-observability; FR13 of
 * tighten-spec-contract.
 */
@UntrustedParser
final class HostBranchProbe {

    private final GitRunner runner;
    private final Path worktreeRoot;
    private final String expectedBranch;

    /**
     * @param runner the git subprocess runner
     * @param worktreeRoot the task worktree root; git commands run with this path as {@code cwd}
     * @param expectedBranch the task branch name gnome commits must still be on, e.g. {@link
     *     TaskIdSanitizer#branchName}
     */
    HostBranchProbe(GitRunner runner, Path worktreeRoot, String expectedBranch) {
        this.runner = runner;
        this.worktreeRoot = worktreeRoot;
        this.expectedBranch = expectedBranch;
    }

    /** The raw {@code rev-parse HEAD} invocation, for the poll. */
    GitCommandResult readHead() {
        return runner.run(worktreeRoot, "rev-parse", "HEAD");
    }

    /** True iff {@code HEAD} is a branch ref matching the task branch. */
    boolean isOnExpectedBranch() {
        GitCommandResult result = runner.run(worktreeRoot, "symbolic-ref", "--short", "HEAD");
        // @UntrustedParser warrant (design D11): the answer is compared with the factory's own
        //     branch name and never handed on as text.
        return result.exitCode() == 0
                && expectedBranch.equals(result.stdout().forParsing().trim());
    }

    /** True iff {@code previousTip} is an ancestor of {@code HEAD}. */
    boolean isAncestor(String previousTip) {
        return runner.run(worktreeRoot, "merge-base", "--is-ancestor", previousTip, "HEAD")
                        .exitCode()
                == 0;
    }
}
