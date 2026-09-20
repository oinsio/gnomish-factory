package com.github.oinsio.gnomish.app.port.git;

/**
 * The verdict of the delivery fence a park's terminal tracker write is preceded by (FR4, FR5 of
 * fix-lifecycle-push): either nothing needs saying about the remote, or the human reading the park
 * needs one line about it — because origin is confirmed behind, or because the fence could not
 * establish what origin holds at all.
 *
 * <p>A verdict, not a thrown failure, precisely because an undelivered branch never blocks the
 * park: the tracker write proceeds either way, and the difference is one line in the report.
 *
 * <p>Two shapes are enough although the fence can end in three states, the third being "unknown":
 * that distinction is a park-report concern, not a control-flow one. The next pickup's action is
 * the same for a confirmed-behind branch and an unverified one — re-read origin, push if it is
 * missing — so only the note's wording differs, and the note travels inside {@link Undelivered}.
 *
 * <p>Implements FR4, FR5, UX2 of fix-lifecycle-push; FR7, UX2, UX3 of bound-subprocess-commands.
 */
public sealed interface ParkDeliveryVerdict {

    /**
     * The line this verdict contributes to the park report the human reads: the undelivered note,
     * or nothing at all when the remote carries the park. Lives on the verdict so the mapping from
     * verdict shape to report line is spelled once; its single consumer is {@code
     * GuardedPark.reportText()}, which owns appending the line to every park report — the fresh
     * dispatch's and the deferred-park reconcile's alike.
     *
     * @return the report line, or an empty string when there is nothing to tell the human
     */
    default String reportNote() {
        return this instanceof Undelivered(String note) ? note : "";
    }

    /**
     * Nothing to tell the human about the remote: origin carries the park's commit, or there is no
     * origin to carry it (a purely local run, where delivery is meaningless), or the fence could
     * not read the local tip at all and reported that as its own operator line rather than in the
     * park report.
     *
     * <p>For the next pickup this is the quiet shape — the park report carries no claim about
     * delivery, so a resume elsewhere proceeds on the remote state it finds.
     */
    record Delivered() implements ParkDeliveryVerdict {}

    /**
     * The remote does not demonstrably carry the recorded park once the fence's bounded attempts
     * are spent — either origin itself answered that it is behind, or the push never ran to its
     * own exit and origin's answer could not be obtained, leaving the delivery unknown. Which of
     * the two it was is carried by {@code note}, whose wording the fence owns; the verdict
     * deliberately does not split, because the next pickup's remedy is identical for both.
     *
     * @param note the one-line, operator-facing statement appended to the park report; never blank
     */
    record Undelivered(String note) implements ParkDeliveryVerdict {}
}
