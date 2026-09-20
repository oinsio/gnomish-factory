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
 * Owns the host medium's destructive last step of a completion: removing {@code .gnomish-task/}
 * from the task worktree and its index via {@code git rm -r}, then landing the fixed cleanup
 * commit with the tenure's epoch trailer. {@link GitTaskRepository#finishCleanup(String)} keeps
 * the surrounding transition — worktree resolution and the epoch lookup — and holds no knowledge
 * of what the envelope is or how it is removed. History is untouched: every prior commit stays
 * reachable via {@code git show <sha>:.gnomish-task/...} (M4).
 *
 * <p>Crash consistency ({@code .claude/rules/crash-consistency.md}). This is not a transition of
 * its own but one durable step of a sequence {@link GitTaskRepository} orders: the {@code
 * Completed} outcome commit (durable intent, carrying the pending marker), the tracker finish
 * write, then this removal — destructive last, behind every constructive receipt. Removing the
 * envelope takes the pending marker with it, so the cleaned tip needs no separate receipt.
 *
 * <p>The one kill window is between the {@code git rm} and the commit: the worktree and its index
 * have lost the directory, but nothing durable has moved — an uncommitted worktree is not the
 * medium anyone reads, so what the next pickup sees is the tip it saw before. That frozen state is
 * a named shape of the {@code task-branch-contract} capability, a {@code Completed} tip still
 * carrying its envelope: a finished task awaiting cleanup, never one to re-execute. Its recovery
 * owner is reconcile-on-resume, which rolls forward by calling this step again; the guard below
 * makes the second call on an already-cleaned worktree a no-op, so recovery is idempotent and
 * convergent. The guard reads the worktree rather than the tip, so convergence needs a pickup that
 * materializes the worktree from the tip — any instance but the one whose stale worktree already
 * has the removal staged.
 *
 * <p>Kept in sync with {@link GitObjectsTerminalCommits#cleanUp}: both media test the same thing
 * before doing anything — whether {@code .gnomish-task/}, the directory this step removes, is
 * still there (here in the worktree, there on the tip) rather than any single file inside it — so
 * an already-cleaned branch is the same no-op in either mode; and both log the FR2 anchor line
 * ({@code task lifecycle commit written for task {}: event={}}) after the cleanup commit succeeds,
 * the same shape used for every other lifecycle transition (harden-logging-observability).
 *
 * <p>Implements FR15, M4 of add-git-workflow; FR10, FR13 of harden-task-branch-contract.
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
     * @param epoch the tenure this cleanup belongs to, stamped as a trailer (FR13 of
     *     harden-task-branch-contract); {@code null} where no claim is held
     */
    static void commit(GitProcessRunner runner, Path worktree, String taskId, @Nullable ClaimEpoch epoch) {
        if (!Files.exists(worktree.resolve(GnomishTaskPaths.DIR_NAME))) {
            // Already cleaned: running the destructive step twice equals running it once, which is
            // what lets the completion recovery re-run safely (FR10 of harden-task-branch-contract).
            log.debug("cleanup commit for task {} is a no-op: the worktree carries no envelope", taskId);
            return;
        }
        GitCommandResult rm = runner.run(worktree, "rm", "-r", GnomishTaskPaths.DIR_NAME);
        if (rm.exitCode() != 0) {
            throw new GitTaskRepositoryException(
                    taskId, TaskLifecycleEvent.COMPLETED, "git rm -r " + GnomishTaskPaths.DIR_NAME, rm.stderr());
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
