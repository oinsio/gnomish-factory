package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.board.AwaitingHumanRow;
import com.github.oinsio.gnomish.board.BoardLabels;
import com.github.oinsio.gnomish.board.BoardModel;
import com.github.oinsio.gnomish.board.ReadyRow;
import com.github.oinsio.gnomish.board.ReadySummary;
import com.github.oinsio.gnomish.board.WorkingRow;
import java.util.Objects;

/**
 * Renders a {@link BoardModel} as human-readable text (task 4.1): three columns in Ready /
 * Working / AwaitingHuman order (UX1), each Ready row carrying its returned/fresh distinction and
 * eligibility annotation (FR2), a reconciled Ready summary line (FR3), each Working row carrying
 * the holder and claim-freshness age or "freshness unknown" (FR4, design D6), and each
 * AwaitingHuman row carrying its park reason (FR5). Sibling of {@link TaskListRenderer} and
 * {@code StatusTextRenderer}: a plain package-private class with a single {@code render} method,
 * the row labels (claim freshness, truncation marker, eligibility, park reason) delegated to
 * {@link BoardLabels}, shared with the dashboard's HTML renderer so the two surfaces cannot drift.
 *
 * <p>Implements FR2, FR3, FR4, FR5, UX1 of add-board-command.
 */
final class BoardTextRenderer {

    /**
     * Renders {@code model} as a plain-text block: the Ready column (with summary and
     * eligibility annotations), the Working column (holder and claim freshness), and the
     * AwaitingHuman column (park reasons), in that order (UX1).
     *
     * @param model the board model to render; never null
     * @return the rendered text block, ready to print verbatim
     */
    String render(BoardModel model) {
        Objects.requireNonNull(model, "model");
        StringBuilder out = new StringBuilder();
        appendReady(out, model);
        appendWorking(out, model);
        appendAwaitingHuman(out, model);
        return out.toString();
    }

    private void appendReady(StringBuilder out, BoardModel model) {
        out.append("Ready (").append(summaryLine(model.summary())).append(')');
        String marker = BoardLabels.truncationMarker(model);
        if (marker != null) {
            out.append(" [").append(marker).append(']');
        }
        for (ReadyRow row : model.readyRows()) {
            out.append("\n  ").append(row.ref().id()).append(" - ").append(row.title());
            if (row.returned()) {
                out.append(" (returned)");
            }
            String annotation = BoardLabels.eligibilityAnnotation(row.eligibilityReason());
            if (annotation != null) {
                out.append(" — ").append(annotation);
            }
        }
    }

    private String summaryLine(ReadySummary summary) {
        return summary.queuedCount()
                + " queued, "
                + summary.eligibleNowCount()
                + " eligible, "
                + summary.inBackoffCount()
                + " in backoff, "
                + summary.finishedCount()
                + " finished, "
                + summary.wipHeldCount()
                + " WIP-held";
    }

    private void appendWorking(StringBuilder out, BoardModel model) {
        out.append("\nWorking (").append(model.workingRows().size()).append(')');
        for (WorkingRow row : model.workingRows()) {
            out.append("\n  ")
                    .append(row.ref().id())
                    .append(" - ")
                    .append(row.title())
                    .append(" (holder=")
                    .append(row.holder())
                    .append(", ")
                    .append(BoardLabels.claimFreshness(row.claimVersion(), model.generatedAt()))
                    .append(')');
        }
    }

    private void appendAwaitingHuman(StringBuilder out, BoardModel model) {
        out.append("\nAwaitingHuman (").append(model.awaitingHumanRows().size()).append(')');
        for (AwaitingHumanRow row : model.awaitingHumanRows()) {
            out.append("\n  ")
                    .append(row.ref().id())
                    .append(" - ")
                    .append(row.title())
                    .append(" (reason=")
                    .append(BoardLabels.parkReasonLabel(row.reason()))
                    .append(')');
        }
    }
}
