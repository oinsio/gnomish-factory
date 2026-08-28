package com.github.oinsio.gnomish.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.app.branch.BranchShapeDiagnosis;
import com.github.oinsio.gnomish.app.port.git.TaskListRow;
import com.github.oinsio.gnomish.domain.branch.RecoveryDisposition;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Renders {@code gnomish status}' list mode (FR13, spec "Task list mode"): a plain-text table by
 * default, an ad-hoc JSON array with {@code --json}. Unlike the single-task {@code status}
 * report, this view has no durable contract version of its own (design D13 scopes only {@code
 * status.json}/{@code usage.json} as versioned contracts) — it is a CLI convenience view over
 * {@link TaskListRow}, camelCase fields, kept simple per the task's own guidance.
 *
 * <p>Every row names its branch shape (FR16, UX4 of harden-task-branch-contract), and the three
 * quarantine shapes carry the diagnosis {@link BranchShapeDiagnosis} renders for every other
 * consumer — one bad branch is one row that says what is wrong with it, never a failed listing.
 *
 * <p>Implements FR13 of add-git-workflow; FR16, UX4 of harden-task-branch-contract.
 */
final class TaskListRenderer {

    private static final String ROW_FORMAT = "%-30s %-19s %-20s %-9s %s";
    private static final String HEADER = ROW_FORMAT.formatted("TASK", "SHAPE", "STAGE", "ATTEMPTS", "OUTCOME");

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Renders {@code rows} as a plain-text table: one header line, one row per task, "no tasks
     * found" when empty. Column order: task, shape, stage, attempts, outcome.
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
                    .append(ROW_FORMAT.formatted(
                            row.taskId(),
                            row.shape().label(),
                            row.stage() == null ? "-" : row.stage(),
                            row.attemptsUsed(),
                            outcomeCell(row)));
        }
        return out.toString();
    }

    /**
     * The last column: the recorded outcome when there is one, the quarantine diagnosis when the
     * branch refuses to be read, "in progress" for a readable tip with no outcome yet, and "-" for
     * a tip that carries no state to have an outcome in (delivered, bare, pre-contract).
     */
    private static String outcomeCell(TaskListRow row) {
        String outcome = row.outcome();
        if (outcome != null) {
            return outcome;
        }
        if (row.shape().disposition() == RecoveryDisposition.QUARANTINE) {
            return BranchShapeDiagnosis.phrase(row.shape());
        }
        return row.shape().tipCarriesState() ? "in progress" : "-";
    }

    /**
     * Renders {@code rows} as a pretty-printed JSON array, one object per task with fields {@code
     * taskId}, {@code shape}, {@code stage} (nullable), {@code attemptsUsed}, {@code outcome}
     * (nullable) and {@code diagnosis} (non-null only for a quarantine shape).
     *
     * @param rows the deduplicated per-task rows; possibly empty
     * @return the pretty-printed JSON array
     */
    String renderJson(List<TaskListRow> rows) {
        try {
            return mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(
                            rows.stream().map(TaskListRenderer::jsonOf).toList());
        } catch (JsonProcessingException e) {
            // TaskListRow is plain data with no cyclic references or unsupported
            // types, so this is unreachable in practice.
            throw new IllegalStateException("failed to serialize task list", e);
        }
    }

    /**
     * The JSON view of one row. Its assembly lives in the enclosing class rather than as a static
     * factory on the record: PIT's Gregor engine cannot redefine a method declared inside a record
     * without touching the class's {@code Record}/{@code NestHost} attributes, which the JVM
     * refuses (RUN_ERROR, hcoles/pitest#1285) — mapping code that lives one level out is mutated
     * and killed normally, so the gate stays on.
     */
    private record TaskListRowJson(
            String taskId,
            String shape,
            @Nullable String stage,
            int attemptsUsed,
            @Nullable String outcome,
            @Nullable String diagnosis) {}

    private static TaskListRowJson jsonOf(TaskListRow row) {
        return new TaskListRowJson(
                row.taskId(), row.shape().label(), row.stage(), row.attemptsUsed(), row.outcome(), diagnosisOf(row));
    }

    /** The diagnosis a row carries, or null for a shape whose name is the whole answer. */
    private static @Nullable String diagnosisOf(TaskListRow row) {
        return row.shape().disposition() == RecoveryDisposition.QUARANTINE
                ? BranchShapeDiagnosis.phrase(row.shape())
                : null;
    }
}
