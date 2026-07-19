package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;

/**
 * Verifies the round-boundary git protocol (design D12) that keeps gnome commits inside a round
 * safe to build on: still on the task branch, no history rewrite since the last round closed, and
 * {@code .gnomish-task/} left untouched by the gnome. Runs against a remembered "previous tip" —
 * the branch's {@code HEAD} right after the last successful round commit (or the worktree's
 * {@code HEAD} at construction time, for the very first round) — so it only ever inspects
 * committed gnome activity between two tips, never the adapter's own not-yet-staged writes.
 *
 * <p>Any violation throws {@link RoundBoundaryViolationException}, which {@link
 * GitAttemptPersistence#persist} runs before writing {@code state.json}/the trace file, so a
 * violation prevents an inconsistent round commit entirely (FR12).
 *
 * <p>The branch and ancestry checks are also exposed as non-throwing boolean queries ({@link
 * #isOnExpectedBranch()}, {@link #isAncestor(String)}) so {@link BestEffortPush} (NFR-S1) can
 * reuse the exact same git invocations as a skip-with-WARN push precondition, instead of
 * duplicating them.
 *
 * <p>Implements FR12, NFR-S1 of add-git-workflow.
 */
final class RoundBoundaryCheck {

    private final GitProcessRunner runner;
    private final Path worktreeRoot;
    private final String expectedBranch;

    /**
     * @param runner the git subprocess runner
     * @param worktreeRoot the task worktree root; git commands run with this path as {@code cwd}
     * @param expectedBranch the task branch name gnome commits must still be on, e.g. {@link
     *     TaskIdSanitizer#branchName}
     */
    RoundBoundaryCheck(GitProcessRunner runner, Path worktreeRoot, String expectedBranch) {
        this.runner = runner;
        this.worktreeRoot = worktreeRoot;
        this.expectedBranch = expectedBranch;
    }

    /**
     * Verifies the protocol against {@code previousTip}, throwing on the first violation found.
     *
     * @param taskId the task whose worktree is being checked, for the exception message
     * @param previousTip the commit SHA the branch was at right after the previous round closed
     * @throws RoundBoundaryViolationException if HEAD is off the task branch, {@code
     *     previousTip} is not an ancestor of HEAD, or {@code .gnomish-task/} changed since {@code
     *     previousTip}
     */
    void verify(String taskId, String previousTip) {
        checkOnTaskBranch(taskId);
        checkNoHistoryRewrite(taskId, previousTip);
        checkGnomishTaskUntouched(taskId, previousTip);
    }

    /** The worktree's current {@code HEAD} SHA, used as the baseline for the very first round. */
    String currentHead() {
        return runner.run(worktreeRoot, "rev-parse", "HEAD").stdout().trim();
    }

    /**
     * True iff {@code HEAD} is a branch ref matching {@code expectedBranch} — the same {@code git
     * symbolic-ref --short HEAD} check {@link #verify} uses, without the throw.
     */
    boolean isOnExpectedBranch() {
        GitCommandResult result = runner.run(worktreeRoot, "symbolic-ref", "--short", "HEAD");
        return result.exitCode() == 0 && expectedBranch.equals(result.stdout().trim());
    }

    /**
     * True iff {@code previousTip} is an ancestor of {@code HEAD} — the same {@code git merge-base
     * --is-ancestor} check {@link #verify} uses, without the throw.
     */
    boolean isAncestor(String previousTip) {
        return runner.run(worktreeRoot, "merge-base", "--is-ancestor", previousTip, "HEAD")
                        .exitCode()
                == 0;
    }

    private void checkOnTaskBranch(String taskId) {
        if (!isOnExpectedBranch()) {
            throw new RoundBoundaryViolationException(
                    taskId, "HEAD is not on the task branch \"" + expectedBranch + "\"");
        }
    }

    private void checkNoHistoryRewrite(String taskId, String previousTip) {
        if (!isAncestor(previousTip)) {
            throw new RoundBoundaryViolationException(
                    taskId, "previous tip " + previousTip + " is no longer an ancestor of HEAD (history rewrite)");
        }
    }

    private void checkGnomishTaskUntouched(String taskId, String previousTip) {
        GitCommandResult result =
                runner.run(worktreeRoot, "diff", "--name-only", previousTip, "HEAD", "--", ".gnomish-task/");
        if (!result.stdout().trim().isEmpty()) {
            throw new RoundBoundaryViolationException(taskId, ".gnomish-task/ was modified by the gnome");
        }
    }
}
