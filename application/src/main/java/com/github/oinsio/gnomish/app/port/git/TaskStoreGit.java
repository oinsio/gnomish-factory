package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import java.nio.file.Path;

/**
 * The task-store capabilities a use case needs: the lifecycle {@link TaskRepository} for a clone,
 * the round-by-round {@link AttemptPersistence} for a task's worktree, and the reconstructed usage
 * history of a task.
 *
 * <p>The first two are factory methods rather than plain calls because both existing ports carry
 * per-run identity — a repository is rooted at one clone and worktrees root, a persistence at one
 * worktree and taskId. Binding that identity here is what lets a use case ask for the port it needs
 * without naming the backend that implements it (FR12b, design D12 of split-into-modules); it is
 * also what keeps a run's collaborators coherent, since one bound instance hands out collaborators
 * that all share the same underlying backend.
 *
 * <p>Implements FR1, FR14, NFR-C1 of add-git-workflow; FR12b of split-into-modules.
 */
public interface TaskStoreGit {

    /**
     * The lifecycle repository rooted at {@code cloneDir}.
     *
     * @param cloneDir the clone that holds the task branches; never null
     * @param worktreesRoot the root task worktrees are materialized under; never null
     * @return the repository; never null
     */
    TaskLifecycleStore taskRepository(Path cloneDir, Path worktreesRoot);

    /**
     * The round persistence for the task executing in {@code worktree}.
     *
     * @param worktree the task's worktree; never null
     * @param taskId the tracker's original taskId; never null
     * @return the persistence; never null
     */
    AttemptPersistence attemptPersistence(Path worktree, String taskId);

    /**
     * Reads back the last durably recorded task state from {@code worktree} — after a completed
     * run, the last state the round persistence committed.
     *
     * @param worktree the task's worktree; never null
     * @return the recorded state; never null
     */
    TaskState readRecordedState(Path worktree);

    /**
     * Reads back the task's recorded lifecycle from {@code worktree}.
     *
     * @param worktree the task's worktree; never null
     * @return the recorded task record; never null
     */
    TaskRecord readTaskRecord(Path worktree);

    /**
     * Reconstructs {@code taskId}'s per-round usage history.
     *
     * @param cloneDir the clone to walk; never null
     * @param taskId the tracker's original taskId; never null
     * @return the reconstructed history, or a not-found result; never null
     */
    UsageHistoryResult usageHistory(Path cloneDir, String taskId);
}
