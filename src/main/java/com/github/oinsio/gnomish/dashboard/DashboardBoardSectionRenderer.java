package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.board.AwaitingHumanRow;
import com.github.oinsio.gnomish.board.BoardLabels;
import com.github.oinsio.gnomish.board.BoardModel;
import com.github.oinsio.gnomish.board.ReadyRow;
import com.github.oinsio.gnomish.board.WorkingRow;

/**
 * Renders the dashboard page's board section (task 3.1): "unavailable" with
 * the failure summarized when no fetch has ever succeeded ({@link
 * BoardSectionView#model()} {@code null}), otherwise the cached {@link
 * BoardModel}'s Ready/Working/AwaitingHuman rows as pointers only — id,
 * title, and the column's own field (FR10): Ready carries its eligibility
 * annotation and the truncation marker, Working the holder and claim age,
 * AwaitingHuman the park reason — the same labels the CLI board shows, via
 * the shared {@link BoardLabels} (FR5). The section adds the fetch time and,
 * on a refresh failure over an otherwise-usable cache, a refresh-failure
 * notice (FR3, NFR-O1).
 *
 * <p>Implements FR3, FR5, FR10, NFR-O1 of add-dashboard-page (design D6).
 */
final class DashboardBoardSectionRenderer {

    void append(StringBuilder out, BoardSectionView boardView) {
        out.append("<section id=\"board\"><h2>Board</h2>\n");
        if (boardView.model() == null) {
            out.append("<p>unavailable")
                    .append(
                            boardView.failureMessage() == null
                                    ? ""
                                    : ": " + DashboardHtmlFormatter.escape(boardView.failureMessage()))
                    .append("</p>\n");
        } else {
            appendModel(out, boardView.model());
            out.append("<p class=\"timestamp\">board fetched at ")
                    .append(boardView.fetchedAt())
                    .append("</p>\n");
            if (boardView.failureMessage() != null) {
                out.append("<p class=\"refresh-failure\">refresh failed: ")
                        .append(DashboardHtmlFormatter.escape(boardView.failureMessage()))
                        .append("</p>\n");
            }
        }
        out.append("</section>\n");
    }

    private void appendModel(StringBuilder out, BoardModel model) {
        out.append("<h3>Ready</h3>\n");
        String marker = BoardLabels.truncationMarker(model);
        if (marker != null) {
            out.append("<p class=\"truncated\">").append(marker).append("</p>\n");
        }
        out.append("<ul>\n");
        for (ReadyRow row : model.readyRows()) {
            out.append("<li>")
                    .append(DashboardHtmlFormatter.escape(row.ref().id()))
                    .append(" — ")
                    .append(DashboardHtmlFormatter.escape(row.title()));
            String annotation = BoardLabels.eligibilityAnnotation(row.eligibilityReason());
            if (annotation != null) {
                out.append(" (").append(annotation).append(')');
            }
            out.append("</li>\n");
        }
        out.append("</ul>\n<h3>Working</h3><ul>\n");
        for (WorkingRow row : model.workingRows()) {
            out.append("<li>")
                    .append(DashboardHtmlFormatter.escape(row.ref().id()))
                    .append(" — ")
                    .append(DashboardHtmlFormatter.escape(row.title()))
                    .append(" (holder=")
                    .append(DashboardHtmlFormatter.escape(row.holder()))
                    .append(", ")
                    .append(BoardLabels.claimFreshness(row.claimVersion(), model.generatedAt()))
                    .append(")</li>\n");
        }
        out.append("</ul>\n<h3>AwaitingHuman</h3><ul>\n");
        for (AwaitingHumanRow row : model.awaitingHumanRows()) {
            out.append("<li>")
                    .append(DashboardHtmlFormatter.escape(row.ref().id()))
                    .append(" — ")
                    .append(DashboardHtmlFormatter.escape(row.title()))
                    .append(" (reason=")
                    .append(BoardLabels.parkReasonLabel(row.reason()))
                    .append(")</li>\n");
        }
        out.append("</ul>\n");
    }
}
