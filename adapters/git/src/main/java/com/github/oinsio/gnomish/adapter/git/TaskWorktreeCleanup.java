package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides the fate of a task worktree from its terminal {@link TaskOutcome} (FR6, design D6):
 * only {@link TaskOutcome.Completed} removes it via {@code git worktree remove} — the branch
 * itself is untouched, since removal only drops the worktree registration and directory.
 * {@link TaskOutcome.Paused} and {@link TaskOutcome.Escalated} keep it as-is so a resumed or
 * escalated task finds its working copy again. {@link TaskOutcome.Aborted} ALWAYS keeps it,
 * unconditionally — it may hold the only copy of work never durably persisted, so this branch
 * is never merged with the others even though the visible effect ("do nothing") is the same.
 *
 * <p>Kept separate from {@link TaskWorktreeManager}: that class creates/reuses a worktree from a
 * taskId and branch name; this class disposes of one from an already-resolved path and a
 * finished run's outcome — a distinct enough responsibility, and keeping them apart holds each
 * file near the project's file-size target.
 *
 * <p>{@link #pruneWorktrees} is unrelated to outcome-based cleanup: it is unconditional git
 * housekeeping — {@code git worktree prune} — meant to run once when a {@code gnomish run}
 * process starts, to drop registrations left behind when a worktree directory was deleted by
 * something other than {@code git worktree remove} (e.g. an operator's {@code rm -rf}).
 *
 * <p>Implements FR6 of add-git-workflow (design D6).
 */
public final class TaskWorktreeCleanup {

    private static final Logger log = LoggerFactory.getLogger(TaskWorktreeCleanup.class);

    private final GitProcessRunner runner;

    /**
     * @param runner the git subprocess runner
     */
    public TaskWorktreeCleanup(GitProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Removes or keeps {@code worktreePath} depending on {@code outcome} (FR6): only {@link
     * TaskOutcome.Completed} removes it; every other variant is a deliberate no-op. Removing an
     * already-removed or never-registered worktree is a no-op rather than an error, since a
     * resumed or retried cleanup call for the same task is plausible.
     *
     * @param cloneDir the working directory of the clone that owns the worktree registration;
     *     {@code git worktree remove} runs here
     * @param worktreePath the worktree's resolved path, e.g. from {@link
     *     TaskWorktreeManager#ensureWorktree}
     * @param outcome the task run's terminal outcome
     */
    public void cleanUp(Path cloneDir, Path worktreePath, TaskOutcome outcome) {
        switch (outcome) {
            case TaskOutcome.Completed _ -> remove(cloneDir, worktreePath);
            case TaskOutcome.Paused _ -> {
                // Kept as-is: a manual checkpoint may resume from this working copy.
            }
            case TaskOutcome.Escalated _ -> {
                // Kept as-is: an escalated task may resume from this working copy.
            }
            case TaskOutcome.Aborted _ -> {
                // Always kept, unconditionally (design D6): may hold the only copy of work
                // that never reached durable storage.
            }
        }
    }

    /**
     * Runs {@code git worktree prune} in {@code cloneDir} (FR6): unconditional git housekeeping,
     * independent of any task outcome, meant to run once at {@code gnomish run} process start.
     *
     * @param cloneDir the working directory of the clone whose worktree registry is pruned
     */
    public void pruneWorktrees(Path cloneDir) {
        runner.run(cloneDir, "worktree", "prune");
    }

    private void remove(Path cloneDir, Path worktreePath) {
        GitCommandResult removal = runner.run(cloneDir, "worktree", "remove", "--force", worktreePath.toString());
        if (removal.exitCode() != 0) {
            // Best effort — a worktree that will not go away never fails a completed task — but
            // it leaves a directory and a registration behind for every completed task, which is
            // exactly the kind of slow leak nobody finds without a line (FR5).
            // throwable-not-subject: git reported a status, not a thrown fault.
            log.warn(
                    OperatorEvent.WORKTREE_REMOVE_FAILED.head()
                            + "could not remove the worktree at {} (git exited {}); it stays registered until a prune: {}",
                    worktreePath,
                    removal.exitCode(),
                    LogText.forLog(removal.stderr()));
        }
    }
}
