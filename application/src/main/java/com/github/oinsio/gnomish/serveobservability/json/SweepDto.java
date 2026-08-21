package com.github.oinsio.gnomish.serveobservability.json;

import java.util.List;

/**
 * The JSON contract's {@code vitals.sweep} entry (NFR-O1 of add-serve-sandbox-lifecycle): the
 * last sweep tick's completion time, its cadence, its per-category counts, and the bounded
 * kept-environment inventory with {@code keptTotal} stating any truncation.
 *
 * <p>The whole entry renders as {@code null} until the first tick completes, and is absent from
 * documents written by builds that predate this contract — a reader treats both as "no sweep data
 * yet" rather than as zeroes.
 *
 * @param lastTickAt ISO-8601 UTC instant the last sweep tick completed
 * @param intervalSeconds the sweep tick cadence, in seconds — the staleness yardstick for {@code
 *     lastTickAt}, distinct from the top-level {@code intervalSeconds} (the snapshot-write cadence)
 * @param counts the last tick's per-category verdict counts
 * @param kept the bounded kept-environment inventory
 * @param keptTotal how many kept environments the tick saw, before truncation
 * @param consecutiveSkippedTicks ticks in a row that ended without a claim verdict
 */
public record SweepDto(
        String lastTickAt,
        long intervalSeconds,
        SweepCountsDto counts,
        List<KeptEnvironmentDto> kept,
        int keptTotal,
        int consecutiveSkippedTicks) {}
