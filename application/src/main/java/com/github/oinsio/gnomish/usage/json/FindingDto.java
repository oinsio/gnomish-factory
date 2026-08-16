package com.github.oinsio.gnomish.usage.json;

import org.jspecify.annotations.Nullable;

/**
 * The {@code gnomish usage --json} mini-contract's per-finding shape carried under a check's
 * {@code findings} array — mirrors {@code state.json}'s {@code StateFindingDto} field-for-field
 * (design D5), as a distinct class in this package.
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow.
 *
 * @param message what is wrong
 * @param location an optional locator, or {@code null} if none
 * @param details optional extra detail, or {@code null} if none
 */
public record FindingDto(
        String message, @Nullable String location, @Nullable String details) {}
