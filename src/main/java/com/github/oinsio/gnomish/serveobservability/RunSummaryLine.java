package com.github.oinsio.gnomish.serveobservability;

import java.time.Instant;
import java.util.Map;

/**
 * A ledger {@code runSummary} line: written once, for a {@code --drain} run
 * only, when its last slot finishes (FR13). Aggregated in memory at the
 * {@link TaskOutcomeLine} write point (design D6) — the daemon never reads
 * the ledger back to build it (design D5). Standing mode SHALL NOT write
 * this line on any stop; readers aggregate {@link TaskOutcomeLine}s
 * themselves for uptime totals (design D3's since-start-counters rejection).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR13 of add-serve-observability.
 *
 * @param instance the writing process's identity; never null
 * @param startedAt when the drain run started; never null
 * @param finishedAt when the drain run finished; never null
 * @param wallMillis wall-clock duration of the drain run, in milliseconds; never negative
 * @param counts outcome counters accumulated across the run; never null
 * @param tokensByModel token usage summed across every {@link TaskOutcomeLine} of the run;
 *     possibly empty when unreported
 */
public record RunSummaryLine(
        InstanceInfo instance,
        Instant startedAt,
        Instant finishedAt,
        long wallMillis,
        OutcomeCounts counts,
        Map<String, LedgerTokenUsage> tokensByModel)
        implements LedgerLine {

    public RunSummaryLine {
        if (wallMillis < 0) {
            throw new IllegalArgumentException("RunSummaryLine.wallMillis must not be negative");
        }
        tokensByModel = Map.copyOf(tokensByModel);
    }
}
