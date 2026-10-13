package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit;
import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.Optional;

/**
 * The git-subprocess implementation of {@link TaskStoreGit} (FR12b, design D12 of
 * split-into-modules): hands out the per-run {@link TaskLifecycleStore} / {@link AttemptPersistence}
 * bound to one clone or worktree, walks a task's usage history, and reads the two branch documents
 * ({@code task.json}, {@code state.json}) back out of a worktree's branch tip.
 *
 * <p>The handed-out collaborators and the history walk delegate to this package's existing classes
 * over one shared {@link GitProcessRunner}, which is what makes every collaborator a run hands out
 * coherent — the same runner, hence the same per-clone mutation lock (design D8 of
 * add-git-workflow).
 *
 * <p>The two document reads go through {@link GitShowTip} at the task's registered commit (design
 * D1 of fix-envelope-medium; design D13 of tighten-spec-contract) — the commit the task's factory
 * register names, the same commit the branch classifier read. Not the
 * working copy: a worktree is reused as-is between pickups, so its disk may carry a killed
 * predecessor's staged removal or half-written envelope. And not {@code HEAD}: the gnome commits on
 * the worktree's branch, so a {@code Completed} outcome, a gate position or a base pin it wrote
 * there would otherwise be what a resume routes and binds its law on. Reading the registered commit
 * also inherits the seam's invocation gate: a read cut off before git's own exit throws {@link
 * com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException} instead of answering
 * "absent" (NFR-R2).
 *
 * <p>Implements FR1, FR14, NFR-C1 of add-git-workflow; FR12b of split-into-modules; FR1, NFR-R2
 * of fix-envelope-medium; FR9 of add-project-registry; FR13, NFR-S2 of tighten-spec-contract.
 */
public final class GitTaskStore implements TaskStoreGit {

    private final GitRunner runner;
    private final UsageHistoryWalker usageWalker;
    private final ClaimEpochSource epochs;
    private final GitInfrastructureRetry retry;
    private final InstantSource clock;

    /**
     * Package-private: outside this package the writer is built only by {@link
     * GitTaskBackend#taskGit}, over the one tenure record its bundle carries (F74 of
     * tighten-spec-contract), so no assembly can hand the writers a second book.
     *
     * @param runner the git subprocess runner shared across this facade's collaborators; never null
     * @param epochs the tenure the collaborators this facade hands out stamp their commits with
     *     (FR13 of harden-task-branch-contract); {@link ClaimEpochSource#NONE} where no claim is
     *     held — {@code status} and {@code usage} read a branch without one
     * @param retry the infrastructure budget branch lookups and the first push are re-attempted
     *     under; never null
     * @param clock the time source the lifecycle store stamps {@code createdAt} from and the first
     *     push's suppressor measures on — the composition root's one (FR18, FR20 of
     *     supervise-daemon-loops-and-embed-dashboard); never null
     */
    GitTaskStore(GitRunner runner, ClaimEpochSource epochs, GitInfrastructureRetry retry, InstantSource clock) {
        this.runner = runner;
        this.usageWalker = new UsageHistoryWalker(runner, retry);
        this.epochs = epochs;
        this.retry = retry;
        this.clock = clock;
    }

    /**
     * The host-mode lifecycle store, wrapped in the best-effort push every lifecycle commit owes
     * the remote (FR1 of fix-lifecycle-push): the strict {@link GitTaskRepository} records, the
     * decorator replicates, and no caller above this ever sees — or has to remember — the push.
     */
    @Override
    public TaskLifecycleStore taskRepository(RegisteredClone clone) {
        return new PushBestEffortTaskLifecycleStore(
                new GitTaskRepository(runner, clone, epochs, clock), runner, clone.clonePath(), retry, clock);
    }

    @Override
    public AttemptPersistence attemptPersistence(Path worktree, String taskId) {
        return new GitAttemptPersistence(runner, worktree, taskId, epochs);
    }

    @Override
    public Optional<TaskState> readRecordedState(Path worktree, String taskId) {
        return registeredOf(worktree, taskId)
                .flatMap(registered -> registered.readAtRegister(EnvelopePaths.STATE_JSON_PATH))
                .map(StateJsonMapper::readDto)
                .map(StateJsonMapper::fromDto);
    }

    @Override
    public Optional<TaskRecord> readTaskRecord(Path worktree, String taskId) {
        return registeredOf(worktree, taskId)
                .flatMap(registered -> registered.readAtRegister(EnvelopePaths.TASK_JSON_PATH))
                .map(TaskJsonMapper::readDto)
                .map(TaskJsonMapper::fromDto);
    }

    /**
     * The envelope reader for one task: its registered commit, read in the worktree (the ref store
     * is the clone's), or empty when the task has no register — which reads as an absent envelope,
     * exactly as a commit that carries none. The task names the register, never the branch the
     * worktree happens to have checked out.
     */
    private Optional<GitShowTip> registeredOf(Path worktree, String taskId) {
        return new FactoryRegisterRead(runner)
                .registered(worktree, taskId)
                .map(registered -> new GitShowTip(runner, worktree, registered));
    }

    @Override
    public UsageHistoryResult usageHistory(Path cloneDir, String taskId) {
        return usageWalker.walk(cloneDir, taskId);
    }
}
