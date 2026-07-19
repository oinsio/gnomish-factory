package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.GitTaskRepository;
import com.github.oinsio.gnomish.adapter.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonContent;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Small helpers {@link GitModeRunner} needs only around task creation and the post-completion
 * state readback — split out purely to keep {@link GitModeRunner} within the project's file-size
 * guidance (`.claude/rules/process-invariants.md`); the behavior is unchanged from what {@link
 * GitModeRunner} used to do inline.
 *
 * <p>Implements FR6, FR7 of add-git-workflow.
 */
final class GitFreshTaskSupport {

    private GitFreshTaskSupport() {}

    /**
     * Delegates to {@link GitTaskRepository#createTask} — the sole branch/worktree creator on the
     * fresh-run path — remapping its {@link GitTaskRepositoryException} to a {@link
     * UsageException} (exit code 2): on a fresh run, both causes {@code createTask} can throw (an
     * already-existing branch for this taskId, or an unresolved {@code --base}) name an operator
     * mistake, not a resumable condition. {@code base} defaults to the clone's current {@code
     * HEAD} when {@code null}; {@link GitTaskRepository#createTask} requires a non-blank {@code
     * baseRef}, so {@code "HEAD"} is passed through literally rather than {@code null} — matching
     * {@code TaskBranchCreator}'s own "{@code null} means HEAD" convention one layer down.
     */
    static void createTask(
            GitTaskRepository taskRepository, String taskId, TaskContext context, @Nullable String base) {
        try {
            taskRepository.createTask(context, base == null ? "HEAD" : base);
        } catch (GitTaskRepositoryException e) {
            throw new UsageException("could not start git-mode task \"" + taskId + "\": " + e.getMessage()
                    + " — this is a fresh run, not --resume; pick a different --task-id, fix --base, or resume the"
                    + " existing task instead");
        }
    }

    /**
     * Reads back {@code .gnomish-task/state.json} from {@code worktree} after a completed run —
     * the last state the git {@code AttemptPersistence} durably committed.
     */
    static TaskState readFinalState(Path worktree) {
        Path stateJson = worktree.resolve(".gnomish-task").resolve("state.json");
        try {
            String json = Files.readString(stateJson);
            return StateJsonMapper.fromDto(StateJsonMapper.readDto(json));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read back final state.json at " + stateJson, e);
        }
    }

    /** Reads and parses {@code .gnomish-task/task.json} from {@code worktree}. */
    static TaskJsonContent readTaskJson(Path worktree) {
        Path taskJson = worktree.resolve(".gnomish-task").resolve("task.json");
        String json;
        try {
            json = Files.readString(taskJson);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read task.json at " + taskJson, e);
        }
        return TaskJsonMapper.fromDto(TaskJsonMapper.readDto(json));
    }
}
