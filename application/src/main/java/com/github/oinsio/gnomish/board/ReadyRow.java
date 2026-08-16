package com.github.oinsio.gnomish.board;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One Ready-column row: a ready task's identity, title, and the
 * returned/fresh distinction (FR2 of add-board-command), built one-to-one
 * from a single {@link com.github.oinsio.gnomish.app.port.tracker.ReadyTask}
 * entry in {@link BoardModel#build}.
 *
 * <p>{@code eligibilityReason} mirrors the feed's actual skip-reason
 * precedence (design D7): {@code null} means eligible — the feed would claim
 * this task now — and a non-null {@link EligibilityReason} names the first
 * reason it would not, in precedence order (in backoff with its deadline,
 * then {@code finished}, then WIP-held).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR2 of add-board-command.
 *
 * @param ref the task's canonical identity; never null
 * @param title the task's title; never null
 * @param returned true when the task was previously worked and given back
 * @param eligibilityReason the reason the feed would not claim this task now,
 *     or {@code null} for eligible
 */
public record ReadyRow(
        TaskRef ref,
        String title,
        boolean returned,
        @Nullable EligibilityReason eligibilityReason) {

    public ReadyRow {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(title, "title");
    }
}
