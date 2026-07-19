package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository;
import com.github.oinsio.gnomish.adapter.git.TaskWorktreeCleanup;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import java.nio.file.Path;

/**
 * Records a terminal {@link TaskOutcome} through {@link GitTaskRepository#recordOutcome} and then
 * disposes of the task worktree through {@link TaskWorktreeCleanup#cleanUp} — the "record +
 * cleanup" pair every git-mode terminal boundary needs, whether reached by a fresh run ({@link
 * GitModeRunner}) or by a resumed one ({@link GitResumeRunner}, task 4.7). Extracted so both
 * callers share one place that pairs the two calls in the right order (a task must be durably
 * recorded before its worktree is judged safe to remove) rather than duplicating the pairing.
 *
 * <p>Implements FR6, FR8 of add-git-workflow.
 */
final class GitOutcomeRecorder {

    private GitOutcomeRecorder() {}

    /**
     * Durably records {@code outcome} for {@code taskId} via {@code taskRepository}, then applies
     * {@link TaskWorktreeCleanup}'s outcome-driven disposal (FR6: Completed removes the worktree;
     * Escalated/Paused keep it for a fast resume; Aborted always keeps it for forensics).
     *
     * <p>Implements FR6, FR8 of add-git-workflow.
     *
     * @param runner the git subprocess runner cleanup issues {@code git worktree remove}/{@code
     *     prune} through; never null
     * @param taskRepository the lifecycle port outcome is recorded through; never null
     * @param cloneDir the {@code --dir} project clone that owns the worktree registration; never
     *     null
     * @param worktree the task's worktree path; never null
     * @param taskId the task the outcome belongs to; never blank
     * @param outcome the terminal outcome to record; never null
     */
    static void recordAndCleanUp(
            GitProcessRunner runner,
            GitTaskRepository taskRepository,
            Path cloneDir,
            Path worktree,
            String taskId,
            TaskOutcome outcome) {
        taskRepository.recordOutcome(taskId, outcome);
        new TaskWorktreeCleanup(runner).cleanUp(cloneDir, worktree, outcome);
    }
}
