package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
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
 * owner is reconcile-on-resume, which rolls forward by calling this step again.
 *
 * <p>The guard tests the <em>tip</em> (FR3, design D4 of fix-envelope-medium), so the state of the
 * worktree it meets no longer decides anything, and this step converges from all four cells of its
 * own two-command sequence:
 *
 * <table><caption>Cleanup convergence matrix</caption>
 * <tr><th>Tip / worktree</th><th>What happens</th></tr>
 * <tr><td>tip carries the directory, worktree does too</td>
 *     <td>{@code git rm} removes it from index and working tree; the commit lands the removal</td></tr>
 * <tr><td>tip carries it, the removal is already staged (a predecessor killed after its {@code rm})</td>
 *     <td>{@code --ignore-unmatch} makes the {@code rm} a silent exit 0 instead of a {@code pathspec
 *     did not match} failure; the commit lands the staged removal and one INFO line says so</td></tr>
 * <tr><td>tip carries it, the files are gone from disk but the index still tracks them (a
 *     predecessor killed inside {@code rm}, which unlinks before it writes the index)</td>
 *     <td>the {@code rm} matches the index and removes the entries; the missing files are not an
 *     error; the commit lands the removal</td></tr>
 * <tr><td>tip carries no directory</td>
 *     <td>the no-op, in this medium as in the bare-objects one</td></tr>
 * </table>
 *
 * <p>Recovery is therefore idempotent and convergent on <em>any</em> instance, the one whose own
 * stale worktree froze the state included — which is what the report of 2026-09-20 found missing.
 * A kill inside the {@code rm} also leaves an {@code index.lock} behind, which every later git
 * command refuses loudly (NG2); that state never reaches the commit silently.
 *
 * <p>Kept in sync with {@link GitObjectsTerminalCommits#cleanUp}: both media test the tip for
 * {@code .gnomish-task/}, the directory this step removes, rather than any single file inside it —
 * so an already-cleaned branch is the same no-op in either mode; and both log the FR2 anchor line
 * ({@code task lifecycle commit written for task {}: event={}}) after the cleanup commit succeeds,
 * the same shape used for every other lifecycle transition (harden-logging-observability).
 *
 * <p>Implements FR15, M4 of add-git-workflow; FR10, FR13 of harden-task-branch-contract; FR3,
 * NFR-O1 of fix-envelope-medium.
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
        if (!new GitShowTip(runner, worktree, GitObjects.HEAD).carries(EnvelopePaths.DIR_NAME)) {
            // Already cleaned: running the destructive step twice equals running it once, which is
            // what lets the completion recovery re-run safely (FR10 of harden-task-branch-contract).
            log.debug("cleanup commit for task {} is a no-op: the tip carries no envelope", taskId);
            return;
        }
        GitCommandResult rm = runner.run(worktree, "rm", "-r", "--ignore-unmatch", EnvelopePaths.DIR_NAME);
        if (rm.exitCode() != 0) {
            throw new GitTaskRepositoryException(
                    taskId, TaskLifecycleEvent.COMPLETED, "git rm -r " + EnvelopePaths.DIR_NAME, rm.stderr());
        }
        if (rm.stdout().isBlank()) {
            // git rm prints one line per path it removed, so silence here means the index already
            // held the removal — reachable only through a predecessor killed between its rm and its
            // commit, since the guard above just found the directory at the tip. The commit below
            // lands that staged removal (NFR-O1 of fix-envelope-medium).
            log.info("cleanup commit for task {} lands a removal a predecessor already staged", taskId);
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
