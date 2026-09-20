package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.BasePin;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import java.nio.file.Path;

/**
 * The {@link TaskLifecycleStore} decorator: {@link PushBestEffortTaskRepository}'s behavior for the
 * three base lifecycle writes, plus the same best-effort push after the two commits that only a
 * durable, branch-backed store records (design D1's port-shape note of fix-lifecycle-push) — the
 * tracker-write-confirmed commit and the {@code Completed} cleanup commit (FR10 of
 * harden-task-branch-contract). Each is a lifecycle operation of its own with its own push: the
 * terminal tracker write runs between the outcome commit and the cleanup commit, so the two cannot
 * share one push.
 *
 * <p>One decorator class per port rather than one class casting its delegate: the three shared
 * writes are delegated to a {@link PushBestEffortTaskRepository} built over the same delegate, so
 * the push rule exists once and this file holds delegation shims only.
 *
 * <p>Crash consistency: what is durable before the push is the delegate's commit in the factory
 * clone, and only a write that succeeded is pushed — a throwing delegate call propagates untouched
 * and pushes nothing. This decorator adds no durability of its own: a failed push is one WARN
 * inside {@link LifecyclePush}, never a retry and never a thrown exception, so the lifecycle write
 * degrades rather than failing (NFR-R1). The window it leaves is "commit recorded locally, origin
 * behind", and this class does not converge it: {@link OriginReconciliation} delivers the missing
 * commit at the next task touchpoint on whichever instance picks the task up, and a park's delivery
 * fence pushes the outcome commit before the tracker announces the park.
 *
 * <p>Implements FR1, FR2, NFR-O1, NFR-R1 of fix-lifecycle-push; FR10 of harden-task-branch-contract.
 */
public final class PushBestEffortTaskLifecycleStore implements TaskLifecycleStore {

    /**
     * The WARN label for the confirm commit. Not a {@code TaskLifecycleEvent}: that enum is the
     * closed set of writes {@code ServiceCommitMessages.taskEvent} produces a commit message for,
     * and the confirm commit carries its own fixed message from {@code
     * ServiceCommitMessages.trackerWriteConfirmed()} instead — the same reason as the cleanup
     * commit below.
     */
    private static final String TRACKER_WRITE_CONFIRMED = "TRACKER_WRITE_CONFIRMED";

    /**
     * The WARN label for the {@code Completed} cleanup commit, a lifecycle write of its own now that
     * it is the destructive last step of the completion sequence rather than a tail of {@code
     * recordOutcome} (FR10 of harden-task-branch-contract). Not a {@code TaskLifecycleEvent} for the
     * same reason as above: the cleanup commit carries its own fixed message from {@code
     * ServiceCommitMessages}, not an event's.
     */
    private static final String CLEANUP = "CLEANUP";

    private final TaskLifecycleStore delegate;
    private final PushBestEffortTaskRepository base;
    private final LifecyclePush push;
    private final Path cloneDir;

    /**
     * @param delegate the strict lifecycle store the commits are recorded through; never null
     * @param runner the git subprocess seam the push runs over; never null
     * @param cloneDir the factory clone the push runs from; never null
     */
    public PushBestEffortTaskLifecycleStore(TaskLifecycleStore delegate, GitProcessRunner runner, Path cloneDir) {
        this.delegate = delegate;
        this.base = new PushBestEffortTaskRepository(delegate, runner, cloneDir);
        this.push = new LifecyclePush(runner);
        this.cloneDir = cloneDir;
    }

    @Override
    public void createTask(TaskContext context, ObjectId lawCommit, BasePin pin, TaskState initialState) {
        base.createTask(context, lawCommit, pin, initialState);
    }

    @Override
    public void appendDecision(String taskId, Decision decision, TaskState resetState) {
        base.appendDecision(taskId, decision, resetState);
    }

    @Override
    public void recordOutcome(String taskId, TaskOutcome outcome) {
        base.recordOutcome(taskId, outcome);
    }

    @Override
    public void confirmTerminalWrite(String taskId) {
        delegate.confirmTerminalWrite(taskId);
        pushFor(taskId, TRACKER_WRITE_CONFIRMED);
    }

    @Override
    public void finishCleanup(String taskId) {
        delegate.finishCleanup(taskId);
        pushFor(taskId, CLEANUP);
    }

    private void pushFor(String taskId, String event) {
        push.pushAfter(taskId, event, cloneDir, TaskIdSanitizer.branchName(taskId));
    }
}
