package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.LedgerTokenUsage;
import java.util.List;
import java.util.Map;

/**
 * The dashboard page's history-section view model (task 1.3): one {@link
 * DayOutcomeCounts} row per day that had a readable ledger file within the
 * window, oldest first, plus token usage summed by model across the whole
 * window (FR6, design D5). The spec's "summed tokens by model covering them"
 * (scenario: overnight totals at a glance) reads as a window total rather
 * than a per-day breakdown — no scenario asks for per-day token detail, and
 * a per-day map would have no consumer.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR6 of add-dashboard-page (design D5).
 *
 * @param perDay one row per day with a readable ledger file, oldest first; never null, may be empty
 * @param tokensByModel token usage keyed by model id, summed across the whole window; never null, may be empty
 */
public record LedgerHistoryView(List<DayOutcomeCounts> perDay, Map<String, LedgerTokenUsage> tokensByModel) {

    public LedgerHistoryView {
        perDay = List.copyOf(perDay);
        tokensByModel = Map.copyOf(tokensByModel);
    }
}
