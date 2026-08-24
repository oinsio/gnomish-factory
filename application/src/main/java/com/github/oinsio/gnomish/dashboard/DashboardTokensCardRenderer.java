package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.LedgerTokenUsage;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Renders the tokens block: the window's grand total and period in the
 * heading, then one stacked bar per model over cache-read / cache-creation /
 * input / output, captioned with the integer cache share (FR7).
 *
 * <p>Two rules the palette encodes. Spend is never coloured with the status
 * palette — a large number is not an alarm, and reading it as one is exactly
 * the misreading the old red-bar table invited — so the segments come from
 * the {@code --seg-*} tokens only. And a model with no cache traffic at all
 * says so in words rather than showing a 0%, which would read as a cache
 * that exists and is failing.
 *
 * <p>Models are rendered in id order rather than in map order, so a refresh
 * cannot reshuffle the rows under a reader's eye (UX3).
 *
 * <p>Implements FR2, FR7, FR9, UX3 of redesign-dashboard.
 */
final class DashboardTokensCardRenderer {

    private static final String[] SEGMENT_TOKENS = {"--seg-cache-read", "--seg-cache-write", "--seg-in", "--seg-out"};
    private static final String[] SEGMENT_LABELS = {"cache read", "cache creation", "input", "output"};

    void append(StringBuilder out, LedgerHistoryView history) {
        Map<String, LedgerTokenUsage> byModel = new TreeMap<>(history.tokensByModel());
        out.append("<section class=\"card\" id=\"tokens\">\n");
        DashboardBlockChrome.appendHead(out, "Tokens", heading(history, byModel));
        if (byModel.isEmpty()) {
            DashboardBlockChrome.appendEmpty(out, DashboardIcons.DASH, null, "No token usage recorded yet");
        } else {
            byModel.forEach((model, usage) -> appendModel(out, model, usage));
        }
        out.append("</section>\n");
    }

    private static @Nullable String heading(LedgerHistoryView history, Map<String, LedgerTokenUsage> byModel) {
        if (byModel.isEmpty()) {
            return null;
        }
        long grandTotal = 0L;
        for (LedgerTokenUsage usage : byModel.values()) {
            grandTotal = Math.addExact(grandTotal, total(usage));
        }
        StringBuilder heading = new StringBuilder("<span class=\"num\" title=\"")
                .append(grandTotal)
                .append("\">")
                .append(DashboardCompactNumber.format(grandTotal))
                .append("</span>");
        if (!history.perDay().isEmpty()) {
            heading.append(" &middot; ")
                    .append(DashboardHtmlFormatter.escape(
                            history.perDay().getFirst().date() + " — "
                                    + history.perDay().getLast().date()));
        }
        return heading.toString();
    }

    private static void appendModel(StringBuilder out, String model, LedgerTokenUsage usage) {
        long[] values = {usage.cacheRead(), usage.cacheCreation(), usage.input(), usage.output()};
        long total = total(usage);

        out.append("<div class=\"bar-group\"><div class=\"bar-head\"><span>")
                .append(DashboardHtmlFormatter.escape(model))
                .append("</span><span class=\"num bar-head__total\" title=\"")
                .append(total)
                .append("\">")
                .append(DashboardCompactNumber.format(total))
                .append("</span></div>\n<div class=\"bar\" role=\"img\" aria-label=\"")
                .append(DashboardHtmlFormatter.escape(DashboardBar.ariaLabel(values, SEGMENT_LABELS)))
                .append("\">");
        DashboardBar.appendSegments(out, values, SEGMENT_TOKENS);
        out.append("</div>\n<div class=\"bar-note\">")
                .append(DashboardHtmlFormatter.escape(caption(usage)))
                .append("</div></div>\n");
    }

    /** Leads with the cache share, which is the number that changes what an operator would do. */
    private static String caption(LedgerTokenUsage usage) {
        long cached = Math.addExact(usage.cacheRead(), usage.cacheCreation());
        String lead = cached == 0L ? "cache not in use" : DashboardPercentage.of(cached, total(usage)) + "% from cache";
        return lead + " · in " + DashboardCompactNumber.format(usage.input()) + " · out "
                + DashboardCompactNumber.format(usage.output());
    }

    /** addExact: a corrupt ledger that overflows the sum fails the render loudly, never wraps. */
    private static long total(LedgerTokenUsage usage) {
        return Math.addExact(
                Math.addExact(usage.cacheRead(), usage.cacheCreation()), Math.addExact(usage.input(), usage.output()));
    }
}
