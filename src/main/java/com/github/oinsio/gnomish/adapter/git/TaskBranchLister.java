package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Enumerates every {@code gnomish/*} branch already known to git — local {@code refs/heads/} and
 * remote-tracking {@code refs/remotes/origin/}, read-only, no fetch (FR13's list mode is an
 * overview of what the clone already has, unlike single-task lookup's narrow-fetch fallback) —
 * and reduces them to one {@link TaskListRow} per task, local tip preferred when a task has both.
 *
 * <p>The branch/ref name is never parsed for the taskId (design D16: sanitization is lossy); each
 * candidate ref's {@code task.json} is read via {@code git show <ref>:.gnomish-task/task.json} to
 * recover the authoritative taskId, the same idiom {@link BranchStateReader} uses for a single
 * task.
 *
 * <p>Implements FR13 of add-git-workflow.
 */
public final class TaskBranchLister {

    private static final String TASK_JSON_PATH = ".gnomish-task/task.json";
    private static final String STATE_JSON_PATH = ".gnomish-task/state.json";
    private static final String LOCAL_PREFIX = "refs/heads/gnomish/";
    private static final String REMOTE_PREFIX = "refs/remotes/origin/gnomish/";

    private final GitProcessRunner runner;

    public TaskBranchLister(GitProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Lists every distinct task with a {@code gnomish/*} branch in {@code cloneDir}, deduplicated
     * per taskId with the local tip preferred over a remote-tracking one.
     *
     * @param cloneDir the working directory of an existing git clone (the {@code --dir} target)
     * @return one row per task, in the order first encountered (local branches first); empty if
     *     no {@code gnomish/*} branch exists anywhere
     */
    public List<TaskListRow> list(Path cloneDir) {
        Map<String, TaskListRow> byTaskId = new LinkedHashMap<>();
        for (String ref : listRefs(cloneDir, "refs/heads/", LOCAL_PREFIX)) {
            TaskListRow row = readRow(cloneDir, ref);
            byTaskId.put(row.taskId(), row);
        }
        for (String ref : listRefs(cloneDir, "refs/remotes/origin/", REMOTE_PREFIX)) {
            TaskListRow row = readRow(cloneDir, ref);
            byTaskId.putIfAbsent(row.taskId(), row);
        }
        return List.copyOf(byTaskId.values());
    }

    private List<String> listRefs(Path cloneDir, String pattern, String prefix) {
        GitCommandResult result = runner.run(cloneDir, "for-each-ref", "--format=%(refname)", pattern + "gnomish/*");
        if (result.exitCode() != 0) {
            return List.of();
        }
        return result.stdout()
                .lines()
                .filter(line -> !line.isBlank() && line.startsWith(prefix))
                .toList();
    }

    private TaskListRow readRow(Path cloneDir, String ref) {
        var taskContent = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(show(cloneDir, ref, TASK_JSON_PATH)));
        TaskState state = StateJsonMapper.fromDto(StateJsonMapper.readDto(show(cloneDir, ref, STATE_JSON_PATH)));
        return new TaskListRow(
                taskContent.context().taskId(),
                stageName(state),
                state.attemptsUsed(),
                outcomeLabel(taskContent.outcome()));
    }

    private static @Nullable String stageName(TaskState state) {
        return switch (state.position()) {
            case Position.AtStage atStage -> atStage.name();
            case Position.PipelineEnd ignored -> null;
        };
    }

    private static @Nullable String outcomeLabel(@Nullable TaskOutcomeDto outcome) {
        return switch (outcome) {
            case null -> null;
            case TaskOutcomeDto.Completed ignored -> "completed";
            case TaskOutcomeDto.Paused ignored -> "paused";
            case TaskOutcomeDto.Escalated ignored -> "escalated";
            case TaskOutcomeDto.Aborted ignored -> "aborted";
        };
    }

    private String show(Path cloneDir, String ref, String filePath) {
        GitCommandResult result = runner.run(cloneDir, "show", ref + ":" + filePath);
        if (result.exitCode() != 0) {
            throw new BranchStateFileMissingException(ref, filePath, result.stderr());
        }
        return result.stdout();
    }
}
