package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import java.nio.file.Path;
import java.util.Optional;

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
     * Reads back the last durably recorded task state from the tip of {@code worktree}'s branch —
     * after a completed run, the last state the round persistence committed.
     *
     * <p>Absence is a value, not a fault (design D1, D2 of fix-envelope-medium): an empty result
     * means the branch's tip carries no state envelope — a pre-contract tip, or a tip whose
     * cleanup commit removed the state directory. A read that never ran to its own exit is the
     * one failure that escapes, as {@link BranchTipUnavailableException}: it established nothing
     * about the tip, so it must not be read as absence (NFR-R2).
     *
     * <p>Implements FR1, NFR-R2 of fix-envelope-medium.
     *
     * @param worktree the task's worktree; never null
     * @return the recorded state, or empty when the tip carries no state envelope; never null
     * @throws BranchTipUnavailableException if the read did not run to its own exit
     */
    Optional<TaskState> readRecordedState(Path worktree);

    /**
     * Reads back the task's recorded lifecycle from the tip of {@code worktree}'s branch, with the
     * same absence and unavailability contract as {@link #readRecordedState}.
     *
     * <p>Implements FR1, NFR-R2 of fix-envelope-medium.
     *
     * @param worktree the task's worktree; never null
     * @return the recorded task record, or empty when the tip carries no task envelope; never null
     * @throws BranchTipUnavailableException if the read did not run to its own exit
     */
    Optional<TaskRecord> readTaskRecord(Path worktree);

    /**
     * Reconstructs {@code taskId}'s per-round usage history.
     *
     * @param cloneDir the clone to walk; never null
     * @param taskId the tracker's original taskId; never null
     * @return the reconstructed history, or a not-found result; never null
     */
    UsageHistoryResult usageHistory(Path cloneDir, String taskId);
}
