package com.github.oinsio.gnomish.serveobservability.json;

import java.util.Map;

/**
 * The JSON contract's {@code runSummary} ledger line (v1, spec.md): {@code
 * version} (always {@code 1}), {@code type} (always {@code "runSummary"}),
 * {@code instance}, run window, outcome {@code counts}, and summed {@code
 * tokensByModel} — drain runs only (FR10, FR13).
 *
 * <p>Implements FR10, FR13 conventions of add-serve-observability.
 *
 * @param version the contract version; always {@code 1}
 * @param type the line-type discriminator; always {@code "runSummary"}
 * @param instance the writing process's identity
 * @param startedAt ISO-8601 UTC instant the drain run started
 * @param finishedAt ISO-8601 UTC instant the drain run finished
 * @param wallMillis wall-clock duration of the drain run, in milliseconds
 * @param counts outcome counters accumulated across the run
 * @param tokensByModel token usage summed across the run's task outcomes
 */
public record RunSummaryLineDto(
        int version,
        String type,
        InstanceDto instance,
        String startedAt,
        String finishedAt,
        long wallMillis,
        OutcomeCountsDto counts,
        Map<String, TokenUsageDto> tokensByModel) {}
