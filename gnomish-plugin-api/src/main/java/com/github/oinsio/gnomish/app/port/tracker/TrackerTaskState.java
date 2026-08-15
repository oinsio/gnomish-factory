package com.github.oinsio.gnomish.app.port.tracker;

import org.jspecify.annotations.Nullable;

/**
 * The logical task-state dictionary (tracker-port spec, "Logical task-state
 * dictionary and transition matrix"): {@link Ready}, {@link Working}, {@link
 * AwaitingHuman}, {@link Finished}, and {@link Gone} for a task the readiness
 * criterion excludes or that is closed/nonexistent. Transitions are initiated
 * only by the factory or a human — never by the gnome (FR2) — and this sealed
 * hierarchy exists so a caller can switch exhaustively over the dictionary
 * without a scheduler-slot state ever leaking into it.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR2 of add-tracker-port.
 */
public sealed interface TrackerTaskState
        permits TrackerTaskState.Ready,
                TrackerTaskState.Working,
                TrackerTaskState.AwaitingHuman,
                TrackerTaskState.Finished,
                TrackerTaskState.Gone {

    /** The task is unclaimed and eligible for {@code claim} (subject to core backoff policy). */
    record Ready() implements TrackerTaskState {}

    /**
     * The task is claimed by {@code holder}, the claiming instance's identifier.
     *
     * <p>{@code holder} is a plain {@link String}: the port carries the flattened
     * {@link InstanceId#value()} form ({@code <name>-<suffix>}), not the composite
     * {@link InstanceId} type — a working task only needs a reportable holder
     * label (FR2, FR9), so the port stays agnostic to the composite's structure.
     *
     * @param holder the claiming instance's identifier; never blank
     */
    record Working(String holder) implements TrackerTaskState {

        public Working {
            holder = requireNonBlank(holder);
        }

        /**
         * Fails fast on a blank {@code holder}: a working task with no claim holder
         * cannot be reported or refused correctly (FR2, FR9). Kept as an explicit
         * static method rather than inline in the compact constructor: PIT's record
         * filter suppresses all mutations inside a record's canonical constructor,
         * which would silently exempt this validation from the 100% mutation gate.
         */
        private static String requireNonBlank(String value) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("TrackerTaskState.Working.holder must not be blank");
            }
            return value;
        }
    }

    /**
     * The task is parked awaiting a human action; the only exits are a human
     * returning it to {@link Ready} or closing it (which surfaces later as {@link
     * Gone}).
     *
     * @param reason why the task was parked; never null
     */
    record AwaitingHuman(ParkReason reason) implements TrackerTaskState {}

    /** The task reached the pipeline end and was delivered; never touched again (FR18). */
    record Finished() implements TrackerTaskState {}

    /**
     * The task is closed or nonexistent — outside the factory's world entirely,
     * reported as a state rather than as an error (tracker-port spec, "Closed task
     * is Gone").
     *
     * <p>{@code closureReason} carries the tracker's own account of why the task is
     * gone, when it has one — for GitHub, the issue's {@code state_reason} ({@code
     * completed}/{@code not_planned}/{@code reopened}), which the github-tracker spec
     * requires to reach the revocation context (a human closing a claimed issue is a
     * revocation). It is {@code null} when the task is merely nonexistent (a 404 has
     * no reason) or the tracker does not report one; the no-arg {@link #Gone()}
     * spelling means exactly "gone, reason unknown". Kept a plain {@link String}, not
     * an enum: the port stays agnostic to any single tracker's closure vocabulary.
     *
     * @param closureReason why the task is gone, or {@code null} when unknown
     */
    record Gone(@Nullable String closureReason) implements TrackerTaskState {

        /** Gone with no reported reason (a nonexistent task, or a tracker that reports none). */
        public Gone() {
            this(null);
        }
    }
}
