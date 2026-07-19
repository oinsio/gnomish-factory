package com.github.oinsio.gnomish.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.adapter.git.TaskListRow;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Renders {@code gnomish status}' list mode (FR13, spec "Task list mode"): a plain-text table by
 * default, an ad-hoc JSON array with {@code --json}. Unlike the single-task {@code status}
 * report, this view has no durable contract version of its own (design D13 scopes only {@code
 * status.json}/{@code usage.json} as versioned contracts) — it is a CLI convenience view over
 * {@link TaskListRow}, camelCase fields, kept simple per the task's own guidance.
 *
 * <p>Implements FR13 of add-git-workflow.
 */
final class TaskListRenderer {

    private static final String HEADER = "%-30s %-20s %-9s %s".formatted("TASK", "STAGE", "ATTEMPTS", "OUTCOME");

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Renders {@code rows} as a plain-text table: one header line, one row per task, "no tasks
     * found" when empty. Column order: task, stage, attempts, outcome.
     *
     * @param rows the deduplicated per-task rows; possibly empty
     * @return the rendered text block, ready to print verbatim
     */
    String renderText(List<TaskListRow> rows) {
        if (rows.isEmpty()) {
            return "no tasks found";
        }
        StringBuilder out = new StringBuilder(HEADER);
        for (TaskListRow row : rows) {
            out.append('\n')
                    .append("%-30s %-20s %-9d %s"
                            .formatted(
                                    row.taskId(),
                                    row.stage() == null ? "-" : row.stage(),
                                    row.attemptsUsed(),
                                    row.outcome() == null ? "in progress" : row.outcome()));
        }
        return out.toString();
    }

    /**
     * Renders {@code rows} as a pretty-printed JSON array, one object per task with fields {@code
     * taskId}, {@code stage} (nullable), {@code attemptsUsed}, {@code outcome} (nullable).
     *
     * @param rows the deduplicated per-task rows; possibly empty
     * @return the pretty-printed JSON array
     */
    String renderJson(List<TaskListRow> rows) {
        try {
            return mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(rows.stream().map(TaskListRowJson::of).toList());
        } catch (JsonProcessingException e) {
            // TaskListRow is plain data with no cyclic references or unsupported
            // types, so this is unreachable in practice.
            throw new IllegalStateException("failed to serialize task list", e);
        }
    }

    private record TaskListRowJson(
            String taskId,
            @Nullable String stage,
            int attemptsUsed,
            @Nullable String outcome) {
        static TaskListRowJson of(TaskListRow row) {
            return new TaskListRowJson(row.taskId(), row.stage(), row.attemptsUsed(), row.outcome());
        }
    }
}
