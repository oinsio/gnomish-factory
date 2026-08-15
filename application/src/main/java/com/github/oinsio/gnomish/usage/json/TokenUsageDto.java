package com.github.oinsio.gnomish.usage.json;

/**
 * The {@code gnomish usage --json} mini-contract's per-model token shape — mirrors {@code
 * status.json.TokenUsageDto} field-for-field, as a distinct class in this package (design D5).
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 *
 * @param input tokens consumed as input
 * @param output tokens produced as output
 * @param cacheCreation tokens spent writing to the prompt cache
 * @param cacheRead tokens served from the prompt cache
 */
public record TokenUsageDto(long input, long output, long cacheCreation, long cacheRead) {}
