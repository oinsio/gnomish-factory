package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit;
import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The git-subprocess implementation of {@link TaskStoreGit} (FR12b, design D12 of
 * split-into-modules): hands out the per-run {@link TaskLifecycleStore} / {@link AttemptPersistence}
 * bound to one clone or worktree, and walks a task's usage history.
 *
 * <p>All three delegate to this package's existing collaborators over one shared {@link
 * GitProcessRunner}, which is what makes every collaborator a run hands out coherent — the same
 * runner, hence the same per-clone mutation lock (design D8 of add-git-workflow).
 *
 * <p>Implements FR1, FR14, NFR-C1 of add-git-workflow; FR12b of split-into-modules.
 */
public final class GitTaskStore implements TaskStoreGit {

    private static final String TASK_DIR = ".gnomish-task";

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
    public TaskState readRecordedState(Path worktree) {
        Path stateJson = worktree.resolve(TASK_DIR).resolve("state.json");
        return StateJsonMapper.fromDto(StateJsonMapper.readDto(read(stateJson, "state.json")));
    }

    @Override
    public TaskRecord readTaskRecord(Path worktree) {
        Path taskJson = worktree.resolve(TASK_DIR).resolve("task.json");
        return TaskJsonMapper.fromDto(TaskJsonMapper.readDto(read(taskJson, "task.json")));
    }

    private static String read(Path file, String label) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + label + " at " + file, e);
        }
    }

    @Override
    public UsageHistoryResult usageHistory(Path cloneDir, String taskId) {
        return usageWalker.walk(cloneDir, taskId);
    }
}
