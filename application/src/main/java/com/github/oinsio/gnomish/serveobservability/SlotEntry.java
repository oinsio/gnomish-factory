package com.github.oinsio.gnomish.serveobservability;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One occupied slot in the snapshot's {@code slots} section (FR6): a pointer
 * to the task running there, not a report — per-task detail stays with the
 * task branch / {@code gnomish status <id>} (design D3). {@code stage}/
 * {@code attempt} come from the runner's durable-progress path and may lag
 * up to {@code intervalSeconds}; occupancy itself (the entry's presence)
 * never lags, since slot assign/release are immediate-write triggers
 * (design D11).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR6 of add-serve-observability.
 *
 * @param taskId the occupying task's identifier; never blank
 * @param stage the stage the runner last reported, or {@code null} when not
 *     yet known or the pipeline has ended
 * @param attempt the attempt count last reported for {@code stage}
 * @param since when this slot was assigned to {@code taskId}
 */
public record SlotEntry(String taskId, @Nullable String stage, int attempt, Instant since) {}
