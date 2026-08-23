package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.board.BoardLabels;
import com.github.oinsio.gnomish.board.BoardModel;
import com.github.oinsio.gnomish.board.ReadyRow;
import com.github.oinsio.gnomish.board.WorkingRow;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Renders the in-progress block — the page's fourth priority layer: what the
 * gnomes are doing right now and what they will pick up next, as ONE compact
 * row list rather than the two tables the old board section carried (FR5).
 * Working and Ready stay distinguishable by their status dot and by what the
 * row's trailing slot holds — the holder and claim age for a working row, the
 * eligibility note for a ready one — so the block needs no sub-headings and,
 * crucially, only one empty state instead of two.
 *
 * <p>Every field comes from the board model itself and reads the same as
 * {@code gnomish board} reports it, through the shared {@link BoardLabels}:
 * the compact rows drop the CLI's full annotation text, never its semantics.
 * A ready window capped at its limit still says so, since "nothing more is
 * ready" and "more is ready than fits" are different facts.
 *
 * <p>The claim age is the one field the compact row does not take from
 * {@link BoardLabels} verbatim: the CLI bakes a relative phrase, while the
 * page renders the instant itself through {@link DashboardTime} so it ticks
 * once a second and reveals its ISO value on hover (FR8, NFR-R2, design D4).
 *
 * <p>Implements FR2, FR5, FR8 of redesign-dashboard (design D4).
 */
final class DashboardInProgressCardRenderer {

    void append(StringBuilder out, BoardSectionView boardView) {
        BoardModel model = boardView.model();
        out.append("<section class=\"card\" id=\"in-progress\">\n");
        DashboardBlockChrome.appendHead(out, "In progress", DashboardBlockChrome.boardMeta(boardView, null));
        if (model == null) {
            DashboardBlockChrome.appendBoardUnavailable(out, boardView);
        } else if (model.workingRows().isEmpty() && model.readyRows().isEmpty()) {
            DashboardBlockChrome.appendEmpty(
                    out, DashboardIcons.DASH, null, "A slot is free, no ready tasks in the tracker");
        } else {
            appendRows(out, model);
        }
        out.append("</section>\n");
    }

    private static void appendRows(StringBuilder out, BoardModel model) {
        for (WorkingRow row : model.workingRows()) {
            appendRow(out, "", row.ref().id(), row.title(), workingNote(row, model.generatedAt()));
        }
        for (ReadyRow row : model.readyRows()) {
            String annotation = BoardLabels.eligibilityAnnotation(row.eligibilityReason());
            appendRow(
                    out,
                    " row__dot--ready",
                    row.ref().id(),
                    row.title(),
                    annotation == null ? null : DashboardHtmlFormatter.escape(annotation));
        }
        String marker = BoardLabels.truncationMarker(model);
        if (marker != null) {
            out.append("<div class=\"bar-note\">")
                    .append(DashboardHtmlFormatter.escape(marker))
                    .append("</div>\n");
        }
    }

    /**
     * The trailing slot of a working row: the holder, then the claim age as a
     * {@code <time>} element. A missing claim marker carries no instant to
     * render, so it keeps {@link BoardLabels}' own words instead.
     */
    private static String workingNote(WorkingRow row, Instant generatedAt) {
        StringBuilder note = new StringBuilder(DashboardHtmlFormatter.escape(row.holder())).append(" · ");
        ClaimVersion claimVersion = row.claimVersion();
        if (claimVersion == null) {
            return note.append(DashboardHtmlFormatter.escape(BoardLabels.claimFreshness(null, generatedAt)))
                    .toString();
        }
        note.append("updated ");
        DashboardTime.append(note, claimVersion.updatedAt(), null);
        return note.toString();
    }

    /** {@code note} is finished markup — every text part is escaped by its own producer. */
    private static void appendRow(
            StringBuilder out, String dotModifier, String id, String title, @Nullable String note) {
        out.append("<div class=\"row\"><span class=\"row__dot")
                .append(dotModifier)
                .append("\"></span><span class=\"num\">")
                .append(DashboardHtmlFormatter.escape(id))
                .append("</span><span class=\"row__label\">")
                .append(DashboardHtmlFormatter.escape(title))
                .append("</span>");
        if (note != null) {
            out.append("<span class=\"row__age\">").append(note).append("</span>");
        }
        out.append("</div>\n");
    }
}
