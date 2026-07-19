package com.github.oinsio.gnomish.adapter.git.state;

import org.jspecify.annotations.Nullable;

/**
 * The {@code task.json} contract's {@code decisions[]} entry: {@code body},
 * {@code author}, {@code stage}, {@code at} — mirrors the domain's {@link
 * com.github.oinsio.gnomish.domain.engine.Decision} field-for-field (FR3).
 *
 * <p>Implements FR3, FR4 of add-git-workflow.
 *
 * @param body the free-text decision message, carried verbatim
 * @param author who made the decision, or {@code null} if unattributed
 * @param stage the stage the decision pertains to, or {@code null} if unscoped
 * @param at ISO-8601 UTC instant the decision was recorded, or {@code null} if
 *     unknown
 */
public record TaskDecisionDto(
        String body,
        @Nullable String author,
        @Nullable String stage,
        @Nullable String at) {}
