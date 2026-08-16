package com.github.oinsio.gnomish.status.json;

import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's {@code lastDecision} section: {@code text}, {@code author},
 * {@code stage}, {@code at} (spec.md).
 *
 * <p>Implements FR11, M3 of add-manual-run.
 *
 * @param text the free-text decision message
 * @param author who made the decision, or {@code null} if unattributed
 * @param stage the stage the decision pertains to, or {@code null} if unscoped
 * @param at ISO-8601 UTC instant the decision was recorded, or {@code null} if
 *     unknown
 */
public record DecisionDto(
        String text,
        @Nullable String author,
        @Nullable String stage,
        @Nullable String at) {}
