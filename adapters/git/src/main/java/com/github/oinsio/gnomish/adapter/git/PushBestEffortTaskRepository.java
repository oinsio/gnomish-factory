package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import java.nio.file.Path;

/**
 * Decorates a {@link TaskRepository} with the best-effort push every lifecycle commit owes the
 * remote (design D1 of fix-lifecycle-push) — the lifecycle twin of {@link
 * PushBestEffortAttemptPersistence}, which does the same for round commits. One push per lifecycle
 * operation: the {@code Completed} outcome and the cleanup commit its delegate adds behind it share
 * the single push of the resulting tip.
 *
 * <p>The rule lives here rather than in the repositories or at their call sites: recording stays
 * separate from replication, both git realizations (host worktree and sandboxed bare objects) get
 * it from one place, and push stays the adapter's monopoly — no push machinery ever reaches an
 * application call site or a task environment (NFR-S1). Because the push runs inside the decorated
 * call, a caller that signals a tracker write next necessarily does so after the replication
 * attempt (FR2).
 *
 * <p>Only a delegate write that succeeded is pushed: a throwing lifecycle write propagates
 * untouched and pushes nothing — durability is the recorded branch state.
 *
 * <p>Implements FR1, FR2, FR6, NFR-O1, NFR-R1 of fix-lifecycle-push.
 */
// Not a record: this is a behavior-bearing decorator (the constructor synthesizes the LifecyclePush
// seam from the runner argument rather than passing it through), not immutable data, kept as a plain
// final class for parity with its documented sibling PushBestEffortAttemptPersistence.
@SuppressWarnings("ClassCanBeRecord")
public final class PushBestEffortTaskRepository implements TaskRepository {

    private final TaskRepository delegate;
    private final LifecyclePush push;
    private final Path cloneDir;

    /**
     * @param delegate the strict repository the lifecycle commit is recorded through; never null
     * @param runner the git subprocess seam the push runs over; never null
     * @param cloneDir the factory clone the push runs from — the branch ref lives in its shared
     *     ref store whether the commit was written through a worktree or as bare objects
     */
    public PushBestEffortTaskRepository(TaskRepository delegate, GitProcessRunner runner, Path cloneDir) {
        this.delegate = delegate;
        this.push = new LifecyclePush(runner);
        this.cloneDir = cloneDir;
    }

    @Override
    public void createTask(TaskContext context, String baseRef) {
        delegate.createTask(context, baseRef);
        pushFor(context.taskId(), TaskLifecycleEvent.STARTED.name());
    }

    @Override
    public void appendDecision(String taskId, Decision decision) {
        delegate.appendDecision(taskId, decision);
        pushFor(taskId, TaskLifecycleEvent.RESUMED.name());
    }

    @Override
    public void recordOutcome(String taskId, TaskOutcome outcome) {
        delegate.recordOutcome(taskId, outcome);
        pushFor(taskId, eventFor(outcome).name());
    }

    private void pushFor(String taskId, String event) {
        push.pushAfter(taskId, event, cloneDir, TaskIdSanitizer.branchName(taskId));
    }

    private static TaskLifecycleEvent eventFor(TaskOutcome outcome) {
        return switch (outcome) {
            case TaskOutcome.Completed ignored -> TaskLifecycleEvent.COMPLETED;
            case TaskOutcome.Paused ignored -> TaskLifecycleEvent.PAUSED;
            case TaskOutcome.Escalated ignored -> TaskLifecycleEvent.ESCALATED;
            case TaskOutcome.Aborted ignored -> TaskLifecycleEvent.ABORTED;
        };
    }
}
