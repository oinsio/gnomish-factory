package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.app.port.TrackerWrite;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import java.nio.file.Path;

/**
 * Records a terminal {@link TaskOutcome} through {@link TaskRepository#recordOutcome} and then
 * disposes of the task worktree through the git adapter's {@code TaskWorktreeCleanup#cleanUp}
 * (not linkable here: it lives in the {@code adapters/git} module, which {@code application}
 * does not depend on) — the "record +
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
     * Durably records {@code outcome} for {@code taskId} via {@code taskRepository}, runs the
     * terminal-boundary reconciliation for a non-park outcome (FR3 of fix-lifecycle-push), commits
     * the {@code Completed} cleanup, and applies the adapter's {@code TaskWorktreeCleanup}
     * outcome-driven disposal (FR6: Completed removes the worktree; Escalated/Paused keep it for a
     * fast resume; Aborted always keeps it for forensics).
     *
     * <p>This whole-sequence-at-once form belongs to the paths with no external effect between the
     * steps — manual {@code run} and its resume, which have no tracker to write to. {@code take}
     * drives the same steps through the intent→effect→receipt protocol instead, so its cleanup lands
     * behind the tracker write's receipt (FR10 of harden-task-branch-contract).
     *
     * <p>The recording push is the repository decorator's; this last step is the level-based
     * safety net behind it — when origin still lacks the branch tip after the outcome landed (an
     * earlier round's push was lost, this one's failed), the run's last act is one catch-up
     * attempt. An origin already holding the tip costs a single refs read.
     *
     * <p>A park (Escalated/Paused) is exempt from the reconciliation: in {@code take} the delivery
     * fence its caller runs immediately afterwards ({@link TakeEngineExecution}, FR4) is a strict
     * superset of this check over the same unchanged branch tip, and running both would spend a
     * second {@code ls-remote} per park that can change nothing (NFR-C1's cost discipline). A manual
     * {@code run} park ({@link GitModeRunner}, {@link GitResumeContinuation}) relies on the recording
     * push alone; a park left behind origin is delivered by the next run's own terminal boundary
     * (design D8 of make-run-headless, "Crash consistency of the park"). Here — and only here, since
     * {@code take} drives its park through the tracker protocol rather than this method — a park is
     * recorded with {@link TrackerWrite#NONE}: no tracker write follows, so the record carries no
     * pending marker and owes no receipt commit. {@code Completed} keeps {@link TrackerWrite#OWED}:
     * its marker leaves with the envelope in the cleanup commit that follows at once.
     *
     * <p>Implements FR6, FR8 of add-git-workflow; FR3 of fix-lifecycle-push; FR10 of
     * make-run-headless.
     *
     * @param git the task-git capability set: the worktree port cleanup runs through and the
     *     branch port the reconciliation runs through; never null
     * @param taskRepository the lifecycle port outcome is recorded through; never null
     * @param cloneDir the {@code --dir} project clone that owns the worktree registration; never
     *     null
     * @param worktree the task's worktree path; never null
     * @param taskId the task the outcome belongs to; never blank
     * @param outcome the terminal outcome to record; never null
     */
    static void recordAndCleanUp(
            TaskGit git,
            TaskLifecycleStore taskRepository,
            Path cloneDir,
            Path worktree,
            String taskId,
            TaskOutcome outcome) {
        TrackerWrite trackerWrite = TerminalPark.isPark(outcome) ? TrackerWrite.NONE : TrackerWrite.OWED;
        recordIntent(git, taskRepository, cloneDir, taskId, outcome, trackerWrite);
        if (outcome instanceof TaskOutcome.Completed) {
            taskRepository.finishCleanup(taskId);
        }
        git.worktrees().cleanUp(cloneDir, worktree, outcome);
    }

    /**
     * The durable intent of a terminal transition (FR10 of harden-task-branch-contract): the outcome
     * commit, followed — for a non-park outcome — by the level-based remote reconciliation described
     * above. Nothing destructive happens here: the {@code Completed} cleanup commit and the worktree
     * disposal are the sequence's last steps and run only behind the tracker write's receipt, which
     * is why {@code take} drives them separately from this one.
     *
     * <p>Implements FR6, FR8 of add-git-workflow; FR3 of fix-lifecycle-push; FR10 of
     * harden-task-branch-contract.
     *
     * @param git the task-git capability set the reconciliation runs through; never null
     * @param taskRepository the lifecycle port the outcome is recorded through; never null
     * @param cloneDir the {@code --dir} project clone; never null
     * @param taskId the task the outcome belongs to; never blank
     * @param outcome the terminal outcome to record; never null
     * @param trackerWrite whether a tracker write follows the record — {@link TrackerWrite#OWED}
     *     from {@code take}, whose protocol clears the marker on receipt; never null
     */
    static void recordIntent(
            TaskGit git,
            TaskRepository taskRepository,
            Path cloneDir,
            String taskId,
            TaskOutcome outcome,
            TrackerWrite trackerWrite) {
        taskRepository.recordOutcome(taskId, outcome, trackerWrite);
        if (!TerminalPark.isPark(outcome)) {
            git.branches().reconcileRemote(cloneDir, taskId, "terminal-boundary");
        }
    }

    /**
     * The workspace half of the destructive tail: the outcome-driven worktree disposal (FR6 —
     * {@code Completed} removes the worktree; {@code Escalated}/{@code Paused} keep it for a fast
     * resume; {@code Aborted} always keeps it for forensics).
     *
     * @param git the task-git capability set the disposal runs through; never null
     * @param cloneDir the {@code --dir} project clone that owns the worktree registration
     * @param worktree the task's worktree path; never null
     * @param outcome the terminal outcome that decides the disposal; never null
     */
    static void disposeWorkspace(TaskGit git, Path cloneDir, Path worktree, TaskOutcome outcome) {
        git.worktrees().cleanUp(cloneDir, worktree, outcome);
    }
}
