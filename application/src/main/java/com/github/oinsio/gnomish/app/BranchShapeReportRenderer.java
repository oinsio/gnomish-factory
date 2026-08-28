package com.github.oinsio.gnomish.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.app.branch.BranchShapeDiagnosis;
import com.github.oinsio.gnomish.domain.branch.BranchShape;
import com.github.oinsio.gnomish.domain.branch.RecoveryDisposition;
import org.jspecify.annotations.Nullable;

/**
 * Renders single-task {@code gnomish status} for a branch whose tip carries no report to build one
 * from — delivered, bare, pre-contract, or one of the three quarantine shapes (FR16, UX4 of
 * harden-task-branch-contract). The shape is the answer, so it is rendered as one: a two-line text
 * block, or the same fields as JSON under {@code --json}.
 *
 * <p>The diagnosis comes from {@link BranchShapeDiagnosis}, the one renderer every consumer of a
 * shape shares, so a refusal reads the same here, in the log, and in a tracker report.
 *
 * <p>Implements FR16, UX4 of harden-task-branch-contract.
 */
final class BranchShapeReportRenderer {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Renders the shape as a text block.
     *
     * @param taskId the task the shape was classified for; never blank
     * @param shape the classifier's verdict; never null
     * @return the text block, ready to print verbatim — a diagnosis line only where the shape
     *     carries one
     */
    String renderText(String taskId, BranchShape shape) {
        String block = "Task: " + taskId + "\nShape: " + shape.label();
        String diagnosis = diagnosisOf(shape);
        return diagnosis == null ? block : block + "\nDiagnosis: " + diagnosis;
    }

    /**
     * Renders the shape as the {@code --json} object.
     *
     * @param taskId the task the shape was classified for; never blank
     * @param shape the classifier's verdict; never null
     * @return the pretty-printed JSON object with {@code taskId}, {@code shape} and {@code
     *     diagnosis} (null unless the shape refuses inspection)
     */
    String renderJson(String taskId, BranchShape shape) {
        try {
            return mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new ShapeJson(taskId, shape.label(), diagnosisOf(shape)));
        } catch (JsonProcessingException e) {
            // Three strings with no cyclic references: unreachable in practice.
            throw new IllegalStateException("failed to serialize branch shape", e);
        }
    }

    /** The diagnosis a shape carries, or null for a shape whose name is the whole answer. */
    private static @Nullable String diagnosisOf(BranchShape shape) {
        return shape.disposition() == RecoveryDisposition.QUARANTINE ? BranchShapeDiagnosis.phrase(shape) : null;
    }

    private record ShapeJson(
            String taskId, String shape, @Nullable String diagnosis) {}
}
