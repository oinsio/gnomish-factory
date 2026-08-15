package com.github.oinsio.gnomish.board;

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One Working-column row: an open task in {@code Working} state, its title,
 * holder, and claim version (FR4 of add-board-command), built one-to-one
 * from a {@code Working} entry of {@code listOpen} in {@link
 * BoardModel#build}. The holder is not duplicated from the state — it is
 * read once here and carried as a plain field so renderers need not switch
 * on {@link com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState}.
 *
 * <p>{@code claimVersion} is {@code null} when the live claim marker is
 * missing; this row only carries the raw fact through unchanged from {@link
 * com.github.oinsio.gnomish.app.port.tracker.OpenTask#claimVersion()} —
 * rendering it as a freshness age is task 4.1, not this task.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR4 of add-board-command.
 *
 * @param ref the task's canonical identity; never null
 * @param title the task's title; never null
 * @param holder the claiming instance's identifier; never blank
 * @param claimVersion the live claim version, or {@code null} when the
 *     marker is missing
 */
public record WorkingRow(
        TaskRef ref, String title, String holder, @Nullable ClaimVersion claimVersion) {

    public WorkingRow {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(holder, "holder");
    }
}
