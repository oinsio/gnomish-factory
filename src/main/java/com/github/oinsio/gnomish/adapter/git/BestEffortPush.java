package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort push of the task branch to {@code origin} after a round commit (design D11): the
 * round commit already made the round durable locally, so a push is never load-bearing for
 * correctness — it only shortens how long work can go un-mirrored. With no {@code origin} remote
 * configured, the run is purely local and this is a silent no-op (FR11: "no warnings"). With
 * {@code origin} configured, a failed push logs one WARN carrying taskId/stage/round context
 * (NFR-O2) and returns normally; it never throws and never retries.
 *
 * <p>Push is the adapter's exclusive responsibility, coded in factory logic — never expressed as
 * rules for the gnome (NFR-S1). Three hard rules apply, none of which ever throw or abort the
 * round — every failure mode here is a skip-or-WARN:
 *
 * <ul>
 *   <li>the push uses the exact refspec {@code origin gnomish/<task>:gnomish/<task>}, never a
 *       bare branch name that relies on git's implicit refspec inference;
 *   <li>{@code --force} / {@code --force-with-lease} are never used; a non-fast-forward rejection
 *       is just another failed push — one WARN, no retry;
 *   <li>the push only runs once the round-boundary preconditions it depends on are freshly
 *       confirmed: {@code HEAD} is still on the task branch, and {@code previousTip} is still an
 *       ancestor of {@code HEAD}. Both checks are delegated to {@link RoundBoundaryCheck}'s
 *       non-throwing boolean queries so this class never duplicates the underlying git commands.
 *       A failed precondition skips the push with a WARN instead of attempting it — the
 *       round-boundary check that already ran inside {@link GitAttemptPersistence#persist}
 *       remains the authoritative verdict for the round itself.
 * </ul>
 *
 * <p>Implements FR11, NFR-O2, NFR-S1 of add-git-workflow.
 */
final class BestEffortPush {

    private static final Logger log = LoggerFactory.getLogger(BestEffortPush.class);
    private static final String REMOTE = "origin";

    private final GitProcessRunner runner;

    BestEffortPush(GitProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Pushes {@code branch} to {@code origin} in {@code worktreeRoot} using the exact refspec
     * {@code branch:branch}, if: {@code origin} is configured, {@code roundBoundaryCheck} reports
     * {@code HEAD} is still on {@code branch}, and {@code previousTip} is still an ancestor of
     * {@code HEAD}. Otherwise does nothing but log a WARN (or, with no {@code origin} configured,
     * does nothing at all — silent, per FR11). Never throws, never retries, never forces.
     *
     * @param taskId the task whose branch is being pushed, for the WARN log context
     * @param stage the current stage name, for the WARN log context
     * @param round the current round number, for the WARN log context
     * @param worktreeRoot the task worktree; git commands run with this path as {@code cwd}
     * @param branch the task branch name to push, e.g. {@link TaskIdSanitizer#branchName}
     * @param roundBoundaryCheck supplies the non-throwing branch/ancestry precondition queries
     * @param previousTip the tip {@code HEAD} must still descend from for the push to proceed
     */
    void pushBestEffort(
            String taskId,
            String stage,
            int round,
            Path worktreeRoot,
            String branch,
            RoundBoundaryCheck roundBoundaryCheck,
            String previousTip) {
        if (!originConfigured(worktreeRoot)) {
            return;
        }

        if (!roundBoundaryCheck.isOnExpectedBranch()) {
            log.warn(
                    "push skipped: taskId={}, stage={}, round={}, branch={}, reason=HEAD is not on the task branch",
                    taskId,
                    stage,
                    round,
                    branch);
            return;
        }

        if (!roundBoundaryCheck.isAncestor(previousTip)) {
            log.warn(
                    "push skipped: taskId={}, stage={}, round={}, branch={}, reason=previous tip {} is not an"
                            + " ancestor of HEAD",
                    taskId,
                    stage,
                    round,
                    branch,
                    previousTip);
            return;
        }

        GitCommandResult push = runner.run(worktreeRoot, "push", REMOTE, branch + ":" + branch);
        if (push.exitCode() != 0) {
            log.warn(
                    "push failed: taskId={}, stage={}, round={}, branch={}, stderr={}",
                    taskId,
                    stage,
                    round,
                    branch,
                    push.stderr().trim());
        }
    }

    private boolean originConfigured(Path worktreeRoot) {
        GitCommandResult result = runner.run(worktreeRoot, "remote", "get-url", REMOTE);
        return result.exitCode() == 0;
    }
}
