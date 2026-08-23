package com.github.oinsio.gnomish.app.port.git;

/**
 * The verdict of the delivery fence a park's terminal tracker write is preceded by (FR4, FR5 of
 * fix-lifecycle-push): either the task branch tip is on the remote, or it could not be delivered
 * within the fence's bounded attempts and the human reading the park needs to be told.
 *
 * <p>A verdict, not a thrown failure, precisely because an undelivered branch never blocks the
 * park: the tracker write proceeds either way, and the difference is one line in the report.
 *
 * <p>Implements FR4, FR5, UX2 of fix-lifecycle-push.
 */
public sealed interface ParkDeliveryVerdict {

    /**
     * The line this verdict contributes to the park report the human reads: the undelivered note,
     * or nothing at all when the remote carries the park. Lives on the verdict so both park writers
     * — the fresh-park dispatch and the deferred-park reconcile — spell the mapping once.
     *
     * @return the report line, or an empty string when there is nothing to tell the human
     */
    default String reportNote() {
        return this instanceof Undelivered(String note) ? note : "";
    }

    /**
     * The remote carries the park's commit — or there is no remote to carry it, which is the same
     * thing for a purely local run: nothing to tell the human about.
     */
    record Delivered() implements ParkDeliveryVerdict {}

    /**
     * The remote is behind the recorded park after the fence exhausted its attempts.
     *
     * @param note the one-line, operator-facing statement appended to the park report; never blank
     */
    record Undelivered(String note) implements ParkDeliveryVerdict {}
}
