package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import java.util.function.IntSupplier;

/**
 * Per-claim open-front re-check (design D5), driven by a claim loop (task
 * 2.3) walking the ordered candidates from {@link
 * FeedPolicy#selectClaimCandidates}. {@link FeedPolicy} gates fresh tasks
 * against a single {@code openFrontCount} snapshot taken once per feed
 * cycle; between that snapshot and a given candidate's actual claim
 * attempt, other racing instances may have claimed fresh tasks too, so the
 * count can be stale by the time this candidate's turn comes up. This class
 * re-reads the count immediately before each fresh candidate's claim
 * attempt so a claim loop skips it if the limit has since been reached
 * (FR6) — the per-claim re-check that, combined with D1's slot-permit
 * ordering, bounds overshoot to at most one fresh task per racing instance
 * (M4).
 *
 * <p>Returned candidates never need the check (FR7 — always claimable,
 * outside the limit): {@link #isStillEligible} does not invoke the supplier
 * for them, so a caller-supplied {@code openFrontCount} backed by a real
 * {@code tracker.listOpen()} call is not paid for on the returned path.
 *
 * <p>This class is pure logic — like {@link FeedPolicy} and {@link
 * BackoffPolicy}, it takes {@code openFrontCount} and {@code wipLimit} as
 * explicit parameters rather than reading the tracker or configuration
 * itself; sourcing the supplier from {@code tracker.listOpen().size()} is
 * the caller's job (task 2.3).
 *
 * <p>Implements FR6, D5, M4 of add-factory-serve.
 */
public final class OpenFrontGate {

    private OpenFrontGate() {}

    /**
     * Whether {@code candidate} is still claimable at the moment its turn
     * comes up in a claim loop (design D5).
     *
     * <p>A returned candidate ({@link ReadyTask#returned()}) is always
     * eligible; {@code openFrontCount} is not invoked in that case. A fresh
     * candidate is eligible iff a freshly re-read open-front count is still
     * below {@code wipLimit}.
     *
     * <p>Implements FR6, D5, M4 of add-factory-serve.
     *
     * @param candidate the claim candidate to evaluate; never null
     * @param openFrontCount supplies the current open-front count on
     *     demand, sourced by the caller from {@code
     *     tracker.listOpen().size()}; never null, invoked at most once, and
     *     only for a fresh candidate
     * @param wipLimit the configured WIP limit W
     * @return {@code true} iff the candidate may be claimed now
     */
    public static boolean isStillEligible(ReadyTask candidate, IntSupplier openFrontCount, int wipLimit) {
        if (candidate.returned()) {
            return true;
        }
        return openFrontCount.getAsInt() < wipLimit;
    }
}
