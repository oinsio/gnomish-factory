package com.github.oinsio.gnomish.app.sandboxlifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What one completed sweep tick observed (NFR-O1, NFR-O2 of add-serve-sandbox-lifecycle): when it
 * finished, its per-category verdict counts, the kept environments it saw (bounded — see {@code
 * keptTotal}), and how many ticks in a row have now ended without a claim verdict.
 *
 * <p>The counts belong to THIS tick alone. Cumulative totals are deliberately absent: the snapshot
 * answers "what did the last pass decide", and history is the ledger's job (design D3 of
 * add-serve-observability rejected since-start counters in the snapshot for the same reason).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements NFR-O1, NFR-O2 of add-serve-sandbox-lifecycle.
 *
 * @param tickAt when the tick completed; never null
 * @param counts per-category verdict counts; categories with no verdict are absent from the map
 * @param kept the kept-environment inventory, oldest first, truncated to the sink's bound
 * @param keptTotal how many kept environments the tick actually saw, before truncation
 * @param consecutiveSkippedTicks ticks in a row (including this one) that ended with at least one
 *     skipped-no-verdict object; {@code 0} when this tick reached verdicts for everything
 */
public record SweepTickRecord(
        Instant tickAt,
        Map<SweepVerdictCategory, Integer> counts,
        List<KeptEnvironment> kept,
        int keptTotal,
        int consecutiveSkippedTicks) {

    public SweepTickRecord {
        counts = Map.copyOf(counts);
        kept = List.copyOf(kept);
    }
}
