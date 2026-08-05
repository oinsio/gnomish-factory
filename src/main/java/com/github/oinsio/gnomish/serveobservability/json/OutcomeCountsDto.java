package com.github.oinsio.gnomish.serveobservability.json;

/**
 * The JSON contract's {@code runSummary.counts} section: outcome counters
 * (FR13).
 *
 * @param delivered count of {@code delivered} results
 * @param awaitingHuman count of {@code awaitingHuman} results
 * @param aborted count of {@code aborted} results
 * @param revoked count of {@code revoked} results
 */
public record OutcomeCountsDto(int delivered, int awaitingHuman, int aborted, int revoked) {}
