package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code Completed} cleanup commit (FR15/M4 of add-git-workflow): removes {@code
 * .gnomish-task/} from the worktree and index via {@code git rm -r}, then commits the fixed
 * cleanup message. History is untouched — every prior commit stays reachable via {@code git show
 * <sha>:.gnomish-task/...}.
 *
 * <p>It is the destructive last step of the completion sequence and runs only after the
 * constructive ones have their receipts, per the crash-consistency ADR; a tip recording {@code
 * Completed} without it is a finished task awaiting cleanup, never one to re-execute.
 *
 * <p>Extracted from {@link GitTaskRepository} purely to keep that class within the project's
 * file-size guidance; the repository still owns worktree resolution.
 *
 * <p>Kept in sync with {@link GitObjectsTerminalCommits#cleanUp}: both media log the FR2 anchor
 * line ({@code task lifecycle commit written for task {}: event={}}) after the cleanup commit
 * succeeds, the same shape used for every other lifecycle transition
 * (harden-logging-observability).
 */
final class CleanupCommit {

    private static final Logger log = LoggerFactory.getLogger(CleanupCommit.class);

    private CleanupCommit() {}

    /**
     * Removes {@code .gnomish-task/} from {@code worktree} and commits the removal.
     *
     * @param runner the git subprocess runner
     * @param worktree the task worktree the state directory is removed from
     * @param taskId the task being completed; for error reporting
     * @param epoch the tenure this cleanup belongs to, stamped as a trailer (FR13); {@code null}
     *     where no claim is held
     */
    static void commit(GitProcessRunner runner, Path worktree, String taskId, @Nullable ClaimEpoch epoch) {
        if (!Files.exists(worktree.resolve(".gnomish-task"))) {
            // Already cleaned: running the destructive step twice equals running it once, which is
            // what lets the completion recovery re-run safely (FR10 of harden-task-branch-contract).
            log.debug("cleanup commit for task {} is a no-op: the worktree carries no envelope", taskId);
            return;
        }
        GitCommandResult rm = runner.run(worktree, "rm", "-r", ".gnomish-task");
        if (rm.exitCode() != 0) {
            throw new GitTaskRepositoryException(
                    taskId, TaskLifecycleEvent.COMPLETED, "git rm -r .gnomish-task", rm.stderr());
        }
        GitCommandResult commit =
                runner.run(worktree, "commit", "-m", ClaimEpochTrailer.stamp(ServiceCommitMessages.cleanup(), epoch));
        if (commit.exitCode() != 0) {
            throw new GitTaskRepositoryException(
                    taskId, TaskLifecycleEvent.COMPLETED, "git commit (cleanup)", commit.stderr());
        }
        log.info("task lifecycle commit written for task {}: event={}", taskId, TaskLifecycleEvent.COMPLETED);
    }
}
