package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
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
 * per-run identity — a repository is rooted at one registered clone, a persistence at one
 * worktree and taskId. Binding that identity here is what lets a use case ask for the port it needs
 * without naming the backend that implements it (FR12b, design D12 of split-into-modules); it is
 * also what keeps a run's collaborators coherent, since one bound instance hands out collaborators
 * that all share the same underlying backend.
 *
 * <p>Implements FR1, FR14, NFR-C1 of add-git-workflow; FR12b of split-into-modules; FR3, FR9 of
 * add-project-registry.
 */
public interface TaskStoreGit {

    /**
     * The lifecycle repository rooted at {@code clone}.
     *
     * @param clone the registered clone that holds the task branches; its worktree folder is where
     *     task worktrees are materialized (FR9, NFR-R2 of add-project-registry); never null
     * @return the repository; never null
     */
    TaskLifecycleStore taskRepository(RegisteredClone clone);

    /**
     * The round persistence for the task executing in {@code worktree}.
     *
     * @param worktree the task's worktree; never null
     * @param taskId the tracker's original taskId; never null
     * @return the persistence; never null
     */
    AttemptPersistence attemptPersistence(Path worktree, String taskId);

    /**
     * Reads back the last durably recorded task state at {@code taskId}'s registered commit — the
     * commit its factory register names (design D13 of tighten-spec-contract), never the tip of
     * {@code worktree}'s branch, where the gnome commits; after a completed run, the last state the
     * round persistence committed.
     *
     * <p>Absence is a value, not a fault (design D1, D2 of fix-envelope-medium): an empty result
     * means the registered commit carries no state envelope — a pre-contract commit, or the
     * cleanup commit that removed the state directory — or that the task has no register at all.
     * A read that never ran to its own exit is the one failure that escapes, as {@link
     * BranchTipUnavailableException}: it established nothing, so it must not be read as absence
     * (NFR-R2).
     *
     * <p>Implements FR1, NFR-R2 of fix-envelope-medium; FR13, NFR-S2 of tighten-spec-contract.
     *
     * @param worktree the task's worktree, the repository the read runs in; never null
     * @param taskId the tracker's original taskId, naming the register; never null
     * @return the recorded state, or empty when the registered commit carries no state envelope;
     *     never null
     * @throws BranchTipUnavailableException if the read did not run to its own exit
     */
    Optional<TaskState> readRecordedState(Path worktree, String taskId);

    /**
     * Reads back the task's recorded lifecycle at {@code taskId}'s registered commit, with the
     * same absence and unavailability contract as {@link #readRecordedState} — the outcome, the
     * decisions and the base pin a resume binds its law from all come from that one commit.
     *
     * <p>Implements FR1, NFR-R2 of fix-envelope-medium; FR13, NFR-S2 of tighten-spec-contract.
     *
     * @param worktree the task's worktree, the repository the read runs in; never null
     * @param taskId the tracker's original taskId, naming the register; never null
     * @return the recorded task record, or empty when the registered commit carries no task
     *     envelope; never null
     * @throws BranchTipUnavailableException if the read did not run to its own exit
     */
    Optional<TaskRecord> readTaskRecord(Path worktree, String taskId);

    /**
     * Reconstructs {@code taskId}'s per-round usage history.
     *
     * @param cloneDir the clone to walk; never null
     * @param taskId the tracker's original taskId; never null
     * @return the reconstructed history, or a not-found result; never null
     */
    UsageHistoryResult usageHistory(Path cloneDir, String taskId);
}
