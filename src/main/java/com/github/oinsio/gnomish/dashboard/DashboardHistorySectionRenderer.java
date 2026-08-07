package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.LedgerTokenUsage;
import com.github.oinsio.gnomish.serveobservability.OutcomeCounts;
import java.util.Map;

/**
 * Renders the dashboard page's history section (task 3.1): "no history
 * data" when the ledger window yielded no readable day ({@link
 * LedgerHistoryView#perDay()} empty — FR3 treats an absent or unreadable
 * ledger as an empty section, never an error), otherwise a per-day outcome
 * table, a tokens-by-model table, and the day range the window covers. Each
 * row carries an inline CSS bar sized against the largest row in its table,
 * so relative volume reads at a glance without any external asset (FR6,
 * NFR-O1); the bar width is the one piece of styling that must vary per row,
 * so it is inlined while the shared {@code .bar} rule stays in the page's
 * {@code <style>} block.
 *
 * <p>Implements FR3, FR6, NFR-O1 of add-dashboard-page (design D6).
 */
final class DashboardHistorySectionRenderer {

    /** Widest a magnitude bar may grow, in px; the largest-total row fills it (FR6). */
    private static final int BAR_MAX_PX = 120;

    void append(StringBuilder out, LedgerHistoryView history) {
        out.append("<section id=\"history\"><h2>History</h2>\n");
        if (history.perDay().isEmpty()) {
            out.append("<p>no history data</p>\n");
        } else {
            appendOutcomeTable(out, history);
            appendTokenTable(out, history.tokensByModel());
            out.append("<p class=\"timestamp\">covers ")
                    .append(history.perDay().getFirst().date())
                    .append(" to ")
                    .append(history.perDay().getLast().date())
                    .append("</p>\n");
        }
        out.append("</section>\n");
    }

    private void appendOutcomeTable(StringBuilder out, LedgerHistoryView history) {
        long max = 0;
        for (DayOutcomeCounts day : history.perDay()) {
            max = Math.max(max, dayTotal(day.counts()));
        }
        out.append("<table><tr><th>date</th><th>delivered</th><th>awaitingHuman</th><th>aborted</th>"
                + "<th>revoked</th><th>volume</th></tr>\n");
        for (DayOutcomeCounts day : history.perDay()) {
            out.append("<tr><td>")
                    .append(day.date())
                    .append("</td><td>")
                    .append(day.counts().delivered())
                    .append("</td><td>")
                    .append(day.counts().awaitingHuman())
                    .append("</td><td>")
                    .append(day.counts().aborted())
                    .append("</td><td>")
                    .append(day.counts().revoked())
                    .append("</td>");
            appendBarCell(out, dayTotal(day.counts()), max);
            out.append("</tr>\n");
        }
        out.append("</table>\n");
    }

    private void appendTokenTable(StringBuilder out, Map<String, LedgerTokenUsage> tokensByModel) {
        long max = 0;
        for (LedgerTokenUsage usage : tokensByModel.values()) {
            max = Math.max(max, usage.input() + usage.output());
        }
        out.append("<table><tr><th>model</th><th>input</th><th>output</th>"
                + "<th>cacheCreation</th><th>cacheRead</th><th>volume</th></tr>\n");
        for (Map.Entry<String, LedgerTokenUsage> entry : tokensByModel.entrySet()) {
            LedgerTokenUsage usage = entry.getValue();
            out.append("<tr><td>")
                    .append(DashboardHtmlFormatter.escape(entry.getKey()))
                    .append("</td><td>")
                    .append(usage.input())
                    .append("</td><td>")
                    .append(usage.output())
                    .append("</td><td>")
                    .append(usage.cacheCreation())
                    .append("</td><td>")
                    .append(usage.cacheRead())
                    .append("</td>");
            appendBarCell(out, usage.input() + usage.output(), max);
            out.append("</tr>\n");
        }
        out.append("</table>\n");
    }

    private static long dayTotal(OutcomeCounts counts) {
        return (long) counts.delivered() + counts.awaitingHuman() + counts.aborted() + counts.revoked();
    }

    private static void appendBarCell(StringBuilder out, long value, long max) {
        // max is the largest of non-negative row totals starting from 0, so it is always >= 0;
        // the guard's exact intent is "the table is empty of volume", i.e. max == 0.
        long width = max == 0 ? 0 : Math.round((double) value / max * BAR_MAX_PX);
        out.append("<td><span class=\"bar\" style=\"width:").append(width).append("px\"></span></td>");
    }
}
