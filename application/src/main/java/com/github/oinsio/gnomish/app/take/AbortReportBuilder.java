package com.github.oinsio.gnomish.app.take;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Builds the human-facing report text for a fuse-tripped infrastructure abort
 * (design D3, D10): the abort {@code cause} that tripped the fuse, the resulting
 * consecutive {@code count}, the configured {@code threshold}, and the timestamp
 * of the previous abort in the streak — "abort history in the report" per the
 * tracker-take spec. A full {@code StatusReport}-integrated renderer is task
 * 5.11's job; this is deliberately minimal, kept in its own file so {@link
 * AbortHandler} stays under the project's per-file line target.
 *
 * <p>The per-abort causes and instances of the WHOLE streak are not reachable
 * here: {@link com.github.oinsio.gnomish.app.port.tracker.AbortFacts} carries
 * only the aggregate {@code count}/{@code lastAbortAt} across instances, by
 * design (see {@code AbortRecord}). Each abort's own cause is recorded as an
 * {@code ABORT} correspondence entry on the task, so the report points the
 * reader there for the full per-abort detail rather than passing off this single
 * triggering cause as the complete history.
 *
 * <p>Implements FR14, NFR-C1 of add-tracker-port.
 */
final class AbortReportBuilder {

    private AbortReportBuilder() {}

    /**
     * Composes the {@code park(INFRA)} report text for a fuse trip: states that
     * the abort threshold was reached, gives the count/threshold pair, the time
     * of the previous abort in the streak (when known), the triggering cause, and
     * a pointer to the task's abort entries where every abort's own cause and
     * instance are recorded — so a human can diagnose the underlying
     * infrastructure problem across the whole streak, not just its last link.
     *
     * @param cause free-text description of the abort that tripped the fuse;
     *     never blank
     * @param count the abort count including this abort; positive
     * @param threshold the configured abort-fuse threshold (K); positive
     * @param lastAbortAt when the previous abort in the streak was recorded, or
     *     {@code null} if none is on record (structurally, a fuse trip implies a
     *     prior abort, but the count/timestamp pairing is an adapter guarantee,
     *     not enforced on the read side)
     * @return finished report text; never blank
     */
    static String build(String cause, int count, int threshold, @Nullable Instant lastAbortAt) {
        var priorAbort = lastAbortAt == null ? "" : " The previous abort was recorded at " + lastAbortAt + ".";
        return "Infrastructure abort fuse tripped: "
                + count
                + " consecutive aborts reached the configured threshold of "
                + threshold
                + ". Most recent cause: "
                + cause
                + "."
                + priorAbort
                + " Each abort's own cause and instance are recorded in this task's abort entries;"
                + " review them for the full history across the streak."
                + " A human fix is needed before this task can resume.";
    }
}
