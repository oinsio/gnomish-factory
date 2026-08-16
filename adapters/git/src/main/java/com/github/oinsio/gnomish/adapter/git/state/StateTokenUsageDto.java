package com.github.oinsio.gnomish.adapter.git.state;

/**
 * The {@code state.json} contract's per-model token shape carried as a value of
 * a {@code tokensByModel} map — mirrors {@code status.json}'s {@code
 * TokenUsageDto} field-for-field, as a distinct class in this package (design
 * D5). Mirrors the domain's {@link com.github.oinsio.gnomish.domain.engine.TokenUsage}
 * field-for-field; every field here is always present — "unreported" is
 * expressed one level up by the containing map being empty, never by a missing
 * entry.
 *
 * <p>Implements FR3, FR4 of add-git-workflow.
 *
 * @param input tokens consumed as input
 * @param output tokens produced as output
 * @param cacheCreation tokens spent writing to the prompt cache
 * @param cacheRead tokens served from the prompt cache
 */
public record StateTokenUsageDto(long input, long output, long cacheCreation, long cacheRead) {}
