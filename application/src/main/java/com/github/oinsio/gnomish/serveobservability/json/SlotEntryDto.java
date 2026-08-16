package com.github.oinsio.gnomish.serveobservability.json;

import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's per-slot entry inside {@code slots.entries} (FR6): a
 * pointer to the occupying task, not a report.
 *
 * @param taskId the occupying task's identifier
 * @param stage the stage the runner last reported, or {@code null}
 * @param attempt the attempt count last reported for {@code stage}
 * @param since ISO-8601 UTC instant the slot was assigned
 */
public record SlotEntryDto(String taskId, @Nullable String stage, int attempt, String since) {}
