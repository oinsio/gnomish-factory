package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import java.nio.file.Path;

/**
 * The {@link TaskLifecycleStore} decorator: {@link PushBestEffortTaskRepository}'s behavior for the
 * three base lifecycle writes, plus the same best-effort push after the tracker-write-confirmed
 * commit that only a durable, branch-backed store records (design D1's port-shape note of
 * fix-lifecycle-push).
 *
 * <p>One decorator class per port rather than one class casting its delegate: the three shared
 * writes are delegated to a {@link PushBestEffortTaskRepository} built over the same delegate, so
 * the push rule exists once and this file holds delegation shims only.
 *
 * <p>Implements FR1, FR2, NFR-O1 of fix-lifecycle-push.
 */
public final class PushBestEffortTaskLifecycleStore implements TaskLifecycleStore {

    /**
     * The WARN label for the confirm commit. Not a {@code TaskLifecycleEvent}: that enum is the
     * closed set of writes {@code ServiceCommitMessages} produces a commit message for, and the
     * confirm commit reuses {@code RESUMED}'s message rather than owning one.
     */
    private static final String TRACKER_WRITE_CONFIRMED = "TRACKER_WRITE_CONFIRMED";

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
    public void createTask(TaskContext context, String baseRef) {
        base.createTask(context, baseRef);
    }

    @Override
    public void appendDecision(String taskId, Decision decision) {
        base.appendDecision(taskId, decision);
    }

    @Override
    public void recordOutcome(String taskId, TaskOutcome outcome) {
        base.recordOutcome(taskId, outcome);
    }

    @Override
    public void confirmTerminalWrite(String taskId) {
        delegate.confirmTerminalWrite(taskId);
        push.pushAfter(taskId, TRACKER_WRITE_CONFIRMED, cloneDir, TaskIdSanitizer.branchName(taskId));
    }
}
