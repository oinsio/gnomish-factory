package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.OutcomeCounts;
import org.jspecify.annotations.Nullable;

/**
 * Renders the outcomes-by-day block: one full-width stacked bar per day
 * showing that day's MIX of delivered / awaiting-a-human / aborted /
 * revoked, with the day's absolute total as a number beside it and one
 * shared legend for all days (FR6).
 *
 * <p>The bars are deliberately not scaled by daily volume, which is the
 * whole point of the change: a two-outcome day and a twenty-outcome day span
 * the same width so their mixes compare directly, and the difference in
 * volume is carried by the numbers, where it cannot be misread as a
 * difference in quality.
 *
 * <p>Implements FR2, FR6, FR9 of redesign-dashboard.
 */
final class DashboardOutcomesCardRenderer {

    private static final String[] SEGMENT_TOKENS = {"--seg-delivered", "--seg-waiting", "--seg-aborted", "--seg-revoked"
    };
    private static final String[] SEGMENT_LABELS = {"delivered", "awaiting a human", "aborted", "revoked"};

    void append(StringBuilder out, LedgerHistoryView history) {
        out.append("<section class=\"card\" id=\"outcomes\">\n");
        DashboardBlockChrome.appendHead(out, "Outcomes by day", dayRange(history));
        if (history.perDay().isEmpty()) {
            DashboardBlockChrome.appendEmpty(out, DashboardIcons.DASH, null, "No finished tasks yet");
        } else {
            history.perDay().forEach(day -> appendDay(out, day));
            appendLegend(out);
        }
        out.append("</section>\n");
    }

    /** The window the block actually covers, so an empty tail day is never mistaken for missing data. */
    private static @Nullable String dayRange(LedgerHistoryView history) {
        if (history.perDay().isEmpty()) {
            return null;
        }
        return DashboardHtmlFormatter.escape(history.perDay().getFirst().date() + " — "
                + history.perDay().getLast().date());
    }

    private static void appendDay(StringBuilder out, DayOutcomeCounts day) {
        OutcomeCounts counts = day.counts();
        long[] values = {counts.delivered(), counts.awaitingHuman(), counts.aborted(), counts.revoked()};
        // addExact: a corrupt ledger that overflows the sum fails the render loudly
        // instead of flowing a wrapped negative total into the bar arithmetic
        long total = Math.addExact(Math.addExact(values[0], values[1]), Math.addExact(values[2], values[3]));

        out.append("<div class=\"bar-group\"><div class=\"bar-head\"><span class=\"num\">")
                .append(DashboardHtmlFormatter.escape(day.date().toString()))
                .append("</span><span class=\"num bar-head__total\" title=\"")
                .append(total)
                .append("\">")
                .append(DashboardCompactNumber.format(total))
                .append("</span></div>\n<div class=\"bar\" role=\"img\" aria-label=\"")
                .append(DashboardHtmlFormatter.escape(DashboardBar.ariaLabel(values, SEGMENT_LABELS)))
                .append("\">");
        DashboardBar.appendSegments(out, values, SEGMENT_TOKENS);
        out.append("</div></div>\n");
    }

    private static void appendLegend(StringBuilder out) {
        out.append("<div class=\"legend\">");
        for (int i = 0; i < SEGMENT_TOKENS.length; i++) {
            out.append("<span><span class=\"legend__swatch\" style=\"background:var(")
                    .append(SEGMENT_TOKENS[i])
                    .append(")\"></span>")
                    .append(DashboardHtmlFormatter.escape(SEGMENT_LABELS[i]))
                    .append("</span>");
        }
        out.append("</div>\n");
    }
}
