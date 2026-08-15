package com.github.oinsio.gnomish.board.json;

import org.jspecify.annotations.Nullable;

/**
 * A Ready row's resolved eligibility (design D7, {@code EligibilityReason}): either
 * {@code {"eligible": true}}, or {@code {"eligible": false, "reason": "...", "deadline":
 * "..."}} naming the feed's own skip-reason precedence.
 *
 * <p>{@code reason} is one of {@code "inBackoff"}, {@code "finished"}, {@code
 * "wipHeld"}, or {@code null} when {@code eligible} is {@code true}. {@code deadline}
 * is the materialized ISO-8601 UTC backoff deadline, present only for {@code
 * "inBackoff"}; {@code null} for every other case. A single record with nullable
 * fields is used rather than a polymorphic DTO hierarchy (unlike {@code
 * ActivityDto}/{@code OutcomeDto}): there are only three reasons, none carries a
 * payload beyond the shared {@code deadline} field, and a flat shape keeps consumers
 * from needing a discriminated-union parser for this one field.
 *
 * <p>Implements FR2, NFR-O1 of add-board-command.
 *
 * @param eligible true when the feed would claim this task now
 * @param reason {@code "inBackoff"} / {@code "finished"} / {@code "wipHeld"}, or
 *     {@code null} when {@code eligible} is {@code true}
 * @param deadline the materialized ISO-8601 UTC backoff deadline, present only when
 *     {@code reason} is {@code "inBackoff"}; {@code null} otherwise
 */
public record EligibilityDto(
        boolean eligible, @Nullable String reason, @Nullable String deadline) {}
