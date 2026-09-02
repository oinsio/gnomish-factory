package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.nio.file.Path;

/**
 * Verifies the round-boundary git protocol (design D12) that keeps gnome commits inside a round
 * safe to build on: still on the task branch, no history rewrite since the last round closed, and
 * {@code .gnomish-task/} left untouched by the gnome. Runs against a remembered "previous tip" —
 * the branch's {@code HEAD} right after the last successful round commit (or the worktree's
 * {@code HEAD} at construction time, for the very first round) — so it only ever inspects
 * committed gnome activity between two tips, never the adapter's own not-yet-staged writes.
 *
 * <p>Verification has three outcomes, never two (FR13 of harden-logging-observability): clean,
 * violated, and <b>cannot-verify</b>. A violation throws {@link RoundBoundaryViolationException},
 * which {@link GitAttemptPersistence#persist} runs before writing {@code state.json}/the trace
 * file, so a violation prevents an inconsistent round commit entirely (FR12). A boundary probe
 * that fails while producing its evidence — the {@code .gnomish-task/} diff exiting non-zero or
 * never running to its own exit — is cannot-verify and throws {@link GitPersistFailedException}
 * instead: the round aborts as an infrastructure failure, no stage attempt is burned and no
 * violation is attributed to the gnome. The branch and ancestry probes need no separate
 * cannot-verify arm — their failure modes (detached HEAD, a rev git refuses) already classify as
 * violations rather than as "clean", which is the outcome this rule exists to make unreachable.
 *
 * <p>Kept in sync with {@link HarvestedBoundaryCheck}: both must apply the same
 * {@code .gnomish-task/} boundary rule with the same three outcomes — a non-zero or non-exiting
 * diff invocation is cannot-verify (a thrown {@link GitPersistFailedException}, the round's
 * infrastructure-failure path), a non-empty diff is a violation, and only a diff that ran to a
 * zero exit with no listed path is clean.
 *
 * <p>The branch and ancestry checks are also exposed as non-throwing boolean queries ({@link
 * #isOnExpectedBranch()}, {@link #isAncestor(String)}) so {@link BestEffortPush} (NFR-S1) can
 * reuse the exact same git invocations as a skip-with-WARN push precondition, instead of
 * duplicating them. The tip read is exposed the same way through {@link #readHead()}: {@link
 * #currentHead()} is the durable-baseline reading that refuses on a failed resolution, {@link
 * #readHead()} is the raw result the read-only mid-round poll classifies for itself (FR13).
 *
 * <p>Implements FR12, NFR-S1 of add-git-workflow; FR13 of harden-logging-observability.
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
     * @param key the round being closed; names the stage/attempt a cannot-verify failure reports
     * @param previousTip the commit SHA the branch was at right after the previous round closed
     * @throws RoundBoundaryViolationException if HEAD is off the task branch, {@code
     *     previousTip} is not an ancestor of HEAD, or {@code .gnomish-task/} changed since {@code
     *     previousTip}
     * @throws GitPersistFailedException if the boundary diff cannot be computed at all
     */
    void verify(String taskId, AttemptKey key, String previousTip) {
        checkOnTaskBranch(taskId);
        checkNoHistoryRewrite(taskId, previousTip);
        checkGnomishTaskUntouched(taskId, key, previousTip);
    }

    /**
     * The worktree's current {@code HEAD} SHA, used as the baseline every later round is checked
     * against. Verified before it is used (FR13 of harden-logging-observability): a blank baseline
     * makes the next round's ancestry probe fail, which would attribute a history rewrite to the
     * gnome on the strength of a read that never answered — so a failed resolution refuses here.
     *
     * @throws com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException if {@code HEAD}
     *     cannot be resolved
     */
    String currentHead() {
        return VerifiedTip.required("HEAD", "rev-parse", readHead());
    }

    /**
     * The raw {@code rev-parse HEAD} invocation behind {@link #currentHead()}, for the read-only
     * poll that must not throw on a failed resolution ({@link MidRoundPushListener}): it classifies
     * the outcome through {@link VerifiedTip#read} and skips the observation instead, so the same
     * git command serves both the durable baseline and the poll without either duplicating it.
     */
    GitCommandResult readHead() {
        return runner.run(worktreeRoot, "rev-parse", "HEAD");
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

    private void checkGnomishTaskUntouched(String taskId, AttemptKey key, String previousTip) {
        GitCommandResult result =
                runner.run(worktreeRoot, "diff", "--name-only", previousTip, "HEAD", "--", ".gnomish-task/");
        // A diff that failed printed no paths for the same reason a clean one prints none, so its
        // empty stdout is not evidence of an untouched state directory: cannot-verify, and the
        // round aborts as infrastructure rather than blaming the gnome for what git never said.
        if (result.termination() != Termination.EXITED || result.exitCode() != 0) {
            throw new GitPersistFailedException(
                    taskId, key.stage(), key.attempt(), "round boundary diff", result.cannotVerifyDetail());
        }
        if (!result.stdout().trim().isEmpty()) {
            throw new RoundBoundaryViolationException(taskId, ".gnomish-task/ was modified by the gnome");
        }
    }
}
