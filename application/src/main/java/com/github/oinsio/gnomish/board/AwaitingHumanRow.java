package com.github.oinsio.gnomish.board;

import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.util.Objects;

/**
 * One AwaitingHuman-column row: a parked open task's identity, title, and
 * park reason (FR5 of add-board-command), built one-to-one from an {@code
 * AwaitingHuman} entry of {@code listOpen} in {@link BoardModel#build}.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR5 of add-board-command.
 *
 * @param ref the task's canonical identity; never null
 * @param title the task's title; never null
 * @param reason why the task was parked; never null
 */
public record AwaitingHumanRow(TaskRef ref, String title, ParkReason reason) {

    public AwaitingHumanRow {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(reason, "reason");
    }
}
