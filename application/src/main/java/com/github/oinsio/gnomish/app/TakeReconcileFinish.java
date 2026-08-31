package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.DeliveredBranchState;
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.FinishTransition;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TerminalWriteRetry;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.nio.file.Path;

/**
 * The completion half of reconcile-on-resume (FR9, FR10 of harden-task-branch-contract): a branch
 * that recorded {@code Completed} while its tracker finish never landed is finished — never
 * re-executed — in the two shapes such a branch can be found in.
 *
 * <ul>
 *   <li>{@code Delivered} — the cleanup commit stripped the envelope, so the delivered state is
 *       recovered from branch history through the {@link TaskBranchGit} port ({@link
 *       #deliverCompleted});
 *   <li>{@code CompletedUncleaned} — the envelope is still at the tip, frozen by a kill between the
 *       outcome commit and the tracker write, so the finish is completed and the cleanup commit
 *       follows it ({@link #finishUncleaned}).
 * </ul>
 *
 * <p>Both go through the same {@link TakeFinishReport#finish} a fresh completion uses — identical
 * report text, identical {@link TakeResult.Delivered}, identical {@link
 * com.github.oinsio.gnomish.app.take.ClaimGuard} pre-write guard — and both are recovered
 * transitions, so the tracker is probed before the write is re-driven and a finish that already
 * landed is never posted twice. Zero engine rounds either way: a {@code Completed} task has no
 * legitimate resume, and re-running its last stage would pay for delivered work (NFR-C1).
 *
 * <p>The park half lives in {@link TakeReconcile}; this class was split from it for file size.
 *
 * <p>Implements FR9, FR10 of harden-task-branch-contract; FR10, D10, NFR-C1 of add-claim-heartbeat.
 */
final class TakeReconcileFinish {

    private TakeReconcileFinish() {}

    /**
     * Posts the deferred finish for a delivered-but-unfinished branch and returns {@link
     * TakeResult.Delivered}, running no engine round (FR10, D10, NFR-C1).
     *
     * <p>Implements FR10, D10, NFR-C1 of add-claim-heartbeat.
     *
     * @param git the task-git capability set the delivered branch state is read through; never null
     * @param cloneDir the project clone; never mutated
     * @param taskId the tracker's original taskId whose branch recorded {@code Completed}
     * @param tracker the tracker port the deferred finish is made through; never null
     * @param ref the task's tracker identity; never null
     * @param instanceId this factory instance's identity, for the pre-write claim check; never null
     * @return the {@link TakeResult.Delivered} the deferred finish produced; never null
     */
    static TakeResult deliverCompleted(
            TaskGit git, Path cloneDir, String taskId, Tracker tracker, TaskRef ref, InstanceId instanceId) {
        DeliveredBranchState delivered = git.branches().readDelivered(cloneDir, taskId);
        var completed = new TaskOutcome.Completed(delivered.finalState());
        // A recovered completion, so the tracker is probed before the write is re-driven (FR10): a
        // task already finished there needs no second finish, only the cleanup this tip already has.
        return TakeFinishReport.finish(
                completed,
                delivered.context(),
                TaskIdSanitizer.branchName(taskId),
                tracker,
                ref,
                instanceId,
                TerminalWriteRetry.system(),
                new FinishTransition.Recovered(() -> {}));
    }

    /**
     * Finishes a tip that records {@code Completed} while its envelope is still there — the {@code
     * CompletedUncleaned} shape, frozen by a kill between the outcome commit and the tracker finish
     * (FR9, FR10). The tracker is probed first, the finish re-driven only when it is genuinely
     * absent, and the cleanup commit — the destructive last step — follows the confirmed write. The
     * engine is never re-entered: every stage of this task passed, and re-running the last one would
     * pay for work already delivered (NFR-C1).
     *
     * <p>Implements FR9, FR10 of harden-task-branch-contract.
     *
     * @param branch the resumed branch recording the completed outcome
     * @param finalState the branch's last durably recorded state, read by the caller's mechanics
     * @param cleanup the destructive tail: the cleanup commit and the workspace disposal behind it
     * @param tracker the tracker port the deferred finish is made through; never null
     * @param ref the task's tracker identity; never null
     * @param instanceId this factory instance's identity, for the pre-write claim check; never null
     * @return the {@link TakeResult.Delivered} the deferred finish produced; never null
     */
    static TakeResult finishUncleaned(
            ResumedBranch branch,
            TaskState finalState,
            Runnable cleanup,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        var completed = new TaskOutcome.Completed(finalState);
        return TakeFinishReport.finish(
                completed,
                branch.context(),
                branch.branchName(),
                tracker,
                ref,
                instanceId,
                TerminalWriteRetry.system(),
                new FinishTransition.Recovered(cleanup));
    }
}
