package com.github.oinsio.gnomish.dashboard;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The chrome every block on the page shares: its heading row with the
 * block's own data timestamp, and the explicit empty state it renders
 * instead of an empty list (FR2). Blocks never appear or disappear with
 * data, so every one of them needs both halves of this, and the board-fed
 * pair needs the same degradation wording — which is why it lives here
 * rather than being written out three times.
 *
 * <p>The board-fed blocks carry the three states the capability's
 * independent-degradation requirement names: a successful fetch shows its
 * time, a refresh failure over a usable cache shows the cached time plus a
 * notice, and a board that never fetched renders the failure summary as the
 * block's empty state instead of rows.
 *
 * <p>Implements FR2, UX1 of redesign-dashboard.
 */
final class DashboardBlockChrome {

    private DashboardBlockChrome() {}

    /**
     * Appends a block heading with a free-text meta slot.
     *
     * @param out the page buffer; never null
     * @param title the block's heading text; never null
     * @param meta the already-escaped meta markup, or {@code null} for none
     */
    static void appendHead(StringBuilder out, String title, @Nullable String meta) {
        out.append("<div class=\"card__head\"><h2 class=\"card__title\">")
                .append(DashboardHtmlFormatter.escape(title))
                .append("</h2>");
        if (meta != null) {
            out.append("<span class=\"card__meta\">").append(meta).append("</span>");
        }
        out.append("</div>\n");
    }

    /**
     * Appends the meta slot of a board-fed block: the board's fetch time, a
     * refresh-failure notice when the last refresh failed over a kept cache,
     * and an optional trailing count.
     *
     * @param boardView the board section's data; never null
     * @param count a count to show beside the time, or {@code null} for none
     * @return the meta markup
     */
    static String boardMeta(BoardSectionView boardView, @Nullable Integer count) {
        StringBuilder meta = new StringBuilder("board: ");
        Instant fetchedAt = boardView.fetchedAt();
        if (fetchedAt == null) {
            meta.append("never fetched");
        } else {
            DashboardTime.append(meta, fetchedAt, null);
        }
        if (boardView.failureMessage() != null && boardView.model() != null) {
            meta.append(" &middot; refresh failed, showing cache");
        }
        if (count != null) {
            meta.append(" &middot; <span class=\"num\">")
                    .append(DashboardCompactNumber.format(count))
                    .append("</span>");
        }
        return meta.toString();
    }

    /**
     * Appends an empty state: a glyph and one sentence saying what is
     * absent, never a blank region (FR2, UX1).
     *
     * @param out the page buffer; never null
     * @param icon the glyph markup; never null
     * @param modifier a modifier class for the block, or {@code null} for the neutral treatment
     * @param sentence the plain-text sentence; never null
     */
    static void appendEmpty(StringBuilder out, String icon, @Nullable String modifier, String sentence) {
        out.append("<div class=\"empty")
                .append(modifier == null ? "" : " " + modifier)
                .append("\">")
                .append(icon)
                .append("<span>")
                .append(DashboardHtmlFormatter.escape(sentence))
                .append("</span></div>\n");
    }

    /**
     * Appends the empty state of a board-fed block whose board never
     * fetched: unavailable, with the tracker failure summarized.
     *
     * @param out the page buffer; never null
     * @param boardView the board section's data, with no model; never null
     */
    static void appendBoardUnavailable(StringBuilder out, BoardSectionView boardView) {
        String failure = boardView.failureMessage();
        appendEmpty(
                out,
                DashboardIcons.DASH,
                null,
                failure == null ? "Board unavailable" : "Board unavailable: " + failure);
    }
}
