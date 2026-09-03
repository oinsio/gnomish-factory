package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.take.FeedPolicy;
import com.github.oinsio.gnomish.app.take.OpenFrontGate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.function.IntSupplier;

/**
 * The constants a feed read is graded against, and the two gradings themselves: {@link
 * FeedPolicy#selectClaimCandidates}'s eligibility filter (design D2) and the per-candidate {@link
 * OpenFrontGate} re-check inside the claim walk.
 *
 * <p>Owned as one object rather than four arguments because {@link FeedCycle} never uses any of
 * them for anything else — it only forwards them to these two calls — and because four of one
 * constructor's parameters buying one decision is what process-invariants.md's parameter-count
 * limit exists to stop. {@code backoffBase} and {@code backoffCap} are also two adjacent {@link
 * Duration}s, a transposition a caller could make silently; named components cannot be swapped
 * unnoticed.
 *
 * <p>Implements D2 of add-factory-serve.
 *
 * @param backoffBase the abort-backoff lower bound (design D10 of add-tracker-port)
 * @param backoffCap the abort-backoff upper bound
 * @param wipLimit the WIP limit W (FR6) — the open-front ceiling both gradings apply
 * @param random the head-zone pick source; seeded in specs, so selection is deterministic
 */
record FeedSelection(Duration backoffBase, Duration backoffCap, int wipLimit, Random random) {

    /**
     * The candidates of one feed read: the backoff filter, the head-zone pick and the WIP ceiling,
     * applied at instant {@code now} (design D2).
     *
     * @param readyTasks the raw feed read; never null
     * @param now the instant the read is graded at; never null
     * @param openFrontCount how many tasks this instance already has open
     * @return the claim candidates in claim order; never null
     */
    List<ReadyTask> candidates(List<ReadyTask> readyTasks, Instant now, int openFrontCount) {
        return FeedPolicy.selectClaimCandidates(
                readyTasks, backoffBase, backoffCap, now, openFrontCount, wipLimit, random);
    }

    /**
     * Whether {@code candidate} still passes the open-front gate at the moment it is reached in the
     * claim walk — the count is re-read per candidate, since a claim earlier in the walk moves it.
     *
     * @param candidate the candidate about to be claimed; never null
     * @param openFrontCount the live open-front count, re-read on each call; never null
     * @return true when the claim may proceed
     */
    boolean isStillEligible(ReadyTask candidate, IntSupplier openFrontCount) {
        return OpenFrontGate.isStillEligible(candidate, openFrontCount, wipLimit);
    }
}
