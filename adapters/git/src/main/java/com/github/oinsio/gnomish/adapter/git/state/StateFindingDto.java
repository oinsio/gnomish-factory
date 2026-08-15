package com.github.oinsio.gnomish.adapter.git.state;

import org.jspecify.annotations.Nullable;

/**
 * The {@code state.json} contract's per-finding shape carried under a check's
 * {@code findings} array — mirrors {@code status.json}'s {@code FindingDto}
 * field-for-field, as a distinct class in this package (design D5). Findings are
 * carried in full, never truncated.
 *
 * <p>Implements FR3, FR4 of add-git-workflow.
 *
 * @param message what is wrong
 * @param location an optional locator, or {@code null} if none
 * @param details optional extra detail, or {@code null} if none
 */
public record StateFindingDto(
        String message, @Nullable String location, @Nullable String details) {}
