package com.github.oinsio.gnomish.status.json;

/**
 * The JSON contract's per-model token shape carried as a value of a {@code
 * tokensByModel} map (spec.md, "JSON contract v1"): the four raw counts a
 * resolved model id reported for one accounting unit — an executor round's
 * {@code usage}/{@code totals}, or a single judge vote. Mirrors the domain's
 * {@link com.github.oinsio.gnomish.domain.engine.TokenUsage} field-for-field;
 * unlike the domain record, every field here is always present — "unreported"
 * is expressed one level up by the containing map being empty, never by a
 * missing entry (design D4, D12).
 *
 * <p>Implements FR5, FR9, D12 of add-agent-executor.
 *
 * @param input tokens consumed as input
 * @param output tokens produced as output
 * @param cacheCreation tokens spent writing to the prompt cache
 * @param cacheRead tokens served from the prompt cache
 */
public record TokenUsageDto(long input, long output, long cacheCreation, long cacheRead) {}
