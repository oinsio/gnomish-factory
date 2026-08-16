package com.github.oinsio.gnomish.board.json;

import org.jspecify.annotations.Nullable;

/**
 * One {@code working} row of the board JSON contract: task id, title, holder, and
 * the claim version's last-update instant.
 *
 * <p>{@code claimUpdatedAt} is {@code null} when the live claim marker is absent (a
 * {@code Working} task whose marker went missing) — JSON {@code null} is itself the
 * explicit "unknown" marker NFR-O1 asks for (see {@link BoardJson}'s note on
 * omitting {@code NON_NULL} inclusion); no separate boolean flag is needed. Text
 * rendering turns this same instant into an age (design D6); this DTO carries the
 * raw instant so a JSON consumer applies its own age/threshold logic.
 *
 * <p>Implements FR4, NFR-O1 of add-board-command.
 *
 * @param id the task's canonical identity ({@code TaskRef.id()})
 * @param title the task's title
 * @param holder the claiming instance's identifier
 * @param claimUpdatedAt ISO-8601 UTC instant of the claim version's last update, or
 *     {@code null} when the marker is absent
 */
public record WorkingRowDto(
        String id, String title, String holder, @Nullable String claimUpdatedAt) {}
