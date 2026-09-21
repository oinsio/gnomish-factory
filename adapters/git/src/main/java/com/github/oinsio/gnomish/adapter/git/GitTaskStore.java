package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit;
import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import java.nio.file.Path;
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
 * <p>The two document reads go through {@link GitShowTip} at the worktree's {@code HEAD} (design
 * D1 of fix-envelope-medium) — the same seam the ref-based readers and the branch classifier
 * already read through, so host mode answers what the branch records rather than what the working
 * copy happens to hold. A worktree is reused as-is between pickups, so its disk may carry a
 * killed predecessor's staged removal or half-written envelope; the tip cannot. Reading the tip
 * also inherits the seam's invocation gate: a read cut off before git's own exit throws {@link
 * com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException} instead of answering
 * "absent" (NFR-R2).
 *
 * <p>Implements FR1, FR14, NFR-C1 of add-git-workflow; FR12b of split-into-modules; FR1, NFR-R2
 * of fix-envelope-medium.
 */
public final class GitTaskStore implements TaskStoreGit {

    private final GitProcessRunner runner;
    private final UsageHistoryWalker usageWalker;
    private final ClaimEpochSource epochs;

    /**
     * @param runner the git subprocess runner shared across this facade's collaborators; never null
     * @param epochs the tenure the collaborators this facade hands out stamp their commits with
     *     (FR13 of harden-task-branch-contract); {@link ClaimEpochSource#NONE} where no claim is
     *     held — {@code status} and {@code usage} read a branch without one
     */
    public GitTaskStore(GitProcessRunner runner, ClaimEpochSource epochs) {
        this.runner = runner;
        this.usageWalker = new UsageHistoryWalker(runner);
        this.epochs = epochs;
    }

    /**
     * The host-mode lifecycle store, wrapped in the best-effort push every lifecycle commit owes
     * the remote (FR1 of fix-lifecycle-push): the strict {@link GitTaskRepository} records, the
     * decorator replicates, and no caller above this ever sees — or has to remember — the push.
     */
    @Override
    public TaskLifecycleStore taskRepository(Path cloneDir, Path worktreesRoot) {
        return new PushBestEffortTaskLifecycleStore(
                new GitTaskRepository(runner, cloneDir, worktreesRoot, epochs), runner, cloneDir);
    }

    @Override
    public AttemptPersistence attemptPersistence(Path worktree, String taskId) {
        return new GitAttemptPersistence(runner, worktree, taskId, epochs);
    }

    @Override
    public Optional<TaskState> readRecordedState(Path worktree) {
        return tipOf(worktree)
                .readAtTip(EnvelopePaths.STATE_JSON_PATH)
                .map(StateJsonMapper::readDto)
                .map(StateJsonMapper::fromDto);
    }

    @Override
    public Optional<TaskRecord> readTaskRecord(Path worktree) {
        return tipOf(worktree)
                .readAtTip(EnvelopePaths.TASK_JSON_PATH)
                .map(TaskJsonMapper::readDto)
                .map(TaskJsonMapper::fromDto);
    }

    /**
     * The envelope reader for one worktree: {@code HEAD} of a task worktree is the checked-out task
     * branch, so this is the very tip the branch classifier read.
     */
    private GitShowTip tipOf(Path worktree) {
        return new GitShowTip(runner, worktree, GitObjects.HEAD);
    }

    @Override
    public UsageHistoryResult usageHistory(Path cloneDir, String taskId) {
        return usageWalker.walk(cloneDir, taskId);
    }
}
