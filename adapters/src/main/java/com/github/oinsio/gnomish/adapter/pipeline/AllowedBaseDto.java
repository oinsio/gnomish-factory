package com.github.oinsio.gnomish.adapter.pipeline;

import org.jspecify.annotations.Nullable;

/**
 * One entry of the {@code task-branch.base.allowed} list (FR1 of add-base-ref-resolution): the
 * ref-name pattern and what the refs it names are for.
 *
 * <p>Both fields are nullable at the wire level: a missing {@code pattern} and an unknown
 * {@code role} are located errors the mapper reports (task 2.1), not deserialization failures.
 *
 * <p>Implements FR1 of add-base-ref-resolution.
 *
 * @param pattern the ref-name pattern, or {@code null} when the key is omitted
 * @param role {@code development} or {@code release}, or {@code null} when omitted — the mapper
 *     defaults it to development
 */
public record AllowedBaseDto(
        @Nullable String pattern, @Nullable String role) {}
