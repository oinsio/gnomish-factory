package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.board.AwaitingHumanRow;
import com.github.oinsio.gnomish.board.BoardLabels;
import com.github.oinsio.gnomish.board.BoardModel;
import java.util.List;

/**
 * Renders the waiting-for-a-human block — the page's third priority layer
 * and, when it holds anything, the loudest element on the page: an accent
 * border and background, the count in the block's meta slot, and one row per
 * parked task (FR4). Empty, it drops to ordinary card chrome and states the
 * all-clear calmly rather than apologetically (UX1): an empty escalation
 * queue is the good outcome, and the block still occupies its position so
 * the page's shape never shifts (FR2).
 *
 * <p>A field the tracker port does not expose is dropped from the row, never
 * filled with a placeholder (FR4). Today that drops two of the four fields
 * the requirement names: {@code AwaitingHumanRow} carries the task ref, the
 * title, and the park-reason category, but no escalation reason text and no
 * escalation instant (Q1). The row is therefore the reason glyph, the id,
 * and the task title — the title in {@code row__label}, deliberately not in
 * the {@code row__reason} slot the escalation reason will claim when the
 * port grows it, so the two never get confused for one another.
 *
 * <p>Implements FR2, FR4, UX1 of redesign-dashboard.
 */
final class DashboardAttentionCardRenderer {

    void append(StringBuilder out, BoardSectionView boardView) {
        BoardModel model = boardView.model();
        List<AwaitingHumanRow> rows = model == null ? List.of() : model.awaitingHumanRows();

        out.append("<section class=\"card")
                .append(rows.isEmpty() ? "" : " card--attention")
                .append("\" id=\"attention\">\n");
        DashboardBlockChrome.appendHead(
                out, "Waiting for a human", DashboardBlockChrome.boardMeta(boardView, rows.size()));
        if (model == null) {
            DashboardBlockChrome.appendBoardUnavailable(out, boardView);
        } else if (rows.isEmpty()) {
            DashboardBlockChrome.appendEmpty(
                    out,
                    DashboardIcons.CHECK,
                    "empty--ok",
                    "The queue is empty — the gnomes are managing on their own");
        } else {
            rows.forEach(row -> appendRow(out, row));
        }
        out.append("</section>\n");
    }

    private static void appendRow(StringBuilder out, AwaitingHumanRow row) {
        out.append("<div class=\"row\" title=\"")
                .append(DashboardHtmlFormatter.escape(BoardLabels.parkReasonLabel(row.reason())))
                .append("\">")
                .append(DashboardIcons.parkReason(row.reason()))
                .append("<span class=\"num\">")
                .append(DashboardHtmlFormatter.escape(row.ref().id()))
                .append("</span><span class=\"row__label\">")
                .append(DashboardHtmlFormatter.escape(row.title()))
                .append("</span></div>\n");
    }
}
