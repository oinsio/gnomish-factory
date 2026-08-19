package com.github.oinsio.gnomish.adapter.git.state;

import org.jspecify.annotations.Nullable;

/**
 * The {@code state.json} contract's per-finding shape — mirrors {@code
 * status.json}'s {@code FindingDto} field-for-field, as a distinct class in this
 * package (design D5). Findings are carried in full, never truncated.
 *
 * <p>Two producers write this shape, and a reader must expect both: a check's
 * {@code findings} array (what a failed verification reported) and an attempt's
 * {@code denials} array (what the round's egress guard blocked; D5 of
 * fix-denial-report-attachment). The shape is identical because a denial reads
 * like any other finding — message, locator, detail — and identical is what lets
 * one mapper serve both.
 *
 * <p>Implements FR3, FR4 of add-git-workflow; FR4 of
 * fix-denial-report-attachment.
 *
 * @param message what is wrong
 * @param location an optional locator, or {@code null} if none
 * @param details optional extra detail, or {@code null} if none
 */
public record StateFindingDto(
        String message, @Nullable String location, @Nullable String details) {}
