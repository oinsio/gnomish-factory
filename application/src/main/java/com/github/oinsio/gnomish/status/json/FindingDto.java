package com.github.oinsio.gnomish.status.json;

import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's per-finding shape carried under a check's {@code findings}
 * array: {@code message}, optional {@code location}, optional {@code details}
 * (spec.md). Findings are carried in full — no truncation.
 *
 * <p>Implements FR11, M3 of add-manual-run.
 *
 * @param message what is wrong
 * @param location an optional locator, or {@code null} if none
 * @param details optional extra detail, or {@code null} if none
 */
public record FindingDto(
        String message, @Nullable String location, @Nullable String details) {}
