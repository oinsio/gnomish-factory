package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause;
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
 * <p>The history is categorized (FR14, NFR-O2 of harden-task-branch-contract):
 * the one counter behind the threshold is split into the runs that crashed and
 * the branch repairs that failed, so an operator reading the park report alone
 * can tell "this task keeps dying mid-round" from "this task cannot be
 * repaired" without opening factory logs.
 *
 * <p>Implements FR14, NFR-C1 of add-tracker-port; FR14, NFR-O2 of
 * harden-task-branch-contract.
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
     * @param cause free-text description of the abort that tripped the fuse, already capped to
     *     the abort-cause budget by {@link AbortHandler} — this builder adds framing to it and
     *     never re-checks the bound; never blank
     * @param category which category the tripping attempt belongs to; never null
     * @param facts the accounting as it stood BEFORE this attempt — the tripping
     *     attempt is added to its own category here; never null
     * @param threshold the configured abort-fuse threshold (K); positive
     * @return finished report text; never blank
     */
    static String build(String cause, RecoveryCause category, AbortFacts facts, int threshold) {
        int crashes = facts.crashCount() + (category == RecoveryCause.INSTANCE_CRASH ? 1 : 0);
        int repairs = facts.recoveryCount() + (category == RecoveryCause.RECOVERY_FAILURE ? 1 : 0);
        return "Infrastructure abort fuse tripped: "
                + (crashes + repairs)
                + " consecutive automatic attempts reached the configured threshold of "
                + threshold
                + " ("
                + crashes
                + " crashed runs, "
                + repairs
                + " failed branch repairs). Most recent cause ("
                + category.wireValue()
                + "): "
                + cause
                + "."
                + priorAttempt(facts.lastAbortAt())
                + " Each abort's own cause and instance are recorded in this task's abort entries;"
                + " review them for the full history across the streak."
                + " A human fix is needed before this task can resume.";
    }

    /**
     * Names when the previous attempt in the streak was recorded, or nothing when none is on record
     * — structurally a fuse trip implies a prior attempt, but the count/timestamp pairing is an
     * adapter guarantee, not enforced on the read side.
     */
    private static String priorAttempt(@Nullable Instant lastAbortAt) {
        return lastAbortAt == null ? "" : " The previous attempt was recorded at " + lastAbortAt + ".";
    }
}
