package com.github.oinsio.gnomish.app.take;

/**
 * Builds the human-facing report text for a fuse-tripped infrastructure abort
 * (design D3, D10): a straightforward composed string carrying the abort
 * {@code cause}, the resulting {@code count}, and the configured {@code
 * threshold} — "abort history in the report" per the tracker-take spec. A full
 * {@code StatusReport}-integrated renderer is task 5.11's job; this is
 * deliberately minimal, kept in its own file so {@link AbortHandler} stays
 * under the project's per-file line target.
 *
 * <p>Implements FR14, NFR-C1 of add-tracker-port.
 */
final class AbortReportBuilder {

    private AbortReportBuilder() {}

    /**
     * Composes the {@code park(INFRA)} report text for a fuse trip: states that
     * the abort threshold was reached, then gives the count/threshold pair and
     * the triggering cause so a human can diagnose the underlying infrastructure
     * problem without digging through tracker history.
     *
     * @param cause free-text description of the abort that tripped the fuse;
     *     never blank
     * @param count the abort count including this abort; positive
     * @param threshold the configured abort-fuse threshold (K); positive
     * @return finished report text; never blank
     */
    static String build(String cause, int count, int threshold) {
        return "Infrastructure abort fuse tripped: "
                + count
                + " consecutive aborts reached the configured threshold of "
                + threshold
                + ". Most recent cause: "
                + cause
                + ". A human fix is needed before this task can resume.";
    }
}
