package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.FeedPolicy;
import com.github.oinsio.gnomish.app.take.FinishedDecline;
import com.github.oinsio.gnomish.app.take.OpenFrontGate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.jspecify.annotations.Nullable;

/**
 * One feed cycle's poll-and-claim mechanics (design D1, D2, D5): the tracker poll, {@link
 * FeedPolicy#selectClaimCandidates} eligibility filter, and the claim-race walk with fall-through
 * on a lost race or a per-candidate {@link OpenFrontGate} rejection — shared verbatim by {@link
 * FeedAutomaton#step()} and {@link FeedAutomaton#drain()}. Extracted only to keep {@link
 * FeedAutomaton} within the file-size limit (process-invariants.md); holds no state of its own
 * beyond its collaborators.
 *
 * <p>Both {@link #poll} and {@link #claimOrAbandon} run their tracker calls through {@link
 * FeedOutageRetry} (NFR-R3): a sustained tracker outage is caught, logged, and retried with
 * backoff instead of propagating out of the shared cycle — since both {@link FeedAutomaton#step()}
 * and {@link FeedAutomaton#drain()} call through this one class, the outage tolerance covers both
 * automatically.
 *
 * <p>Implements FR5, FR9, D1, D2, D5, NFR-R3 of add-factory-serve.
 */
record FeedCycle(
        Tracker tracker,
        InstanceId instanceId,
        SlotLedger slotLedger,
        SlotRunner slotRunner,
        Duration backoffBase,
        Duration backoffCap,
        int wipLimit,
        Random random,
        FeedStateLogger stateLogger,
        FeedOutageRetry outageRetry) {

    /** One poll's raw feed plus the eligibility-filtered candidates. */
    record Poll(List<ReadyTask> readyTasks, int openFrontCount, Instant now, List<ReadyTask> candidates) {}

    /**
     * Polls the tracker once and applies the eligibility filter (design D2), at instant {@code
     * now}. The tracker reads run through {@link FeedOutageRetry} (NFR-R3): a sustained outage
     * retries with backoff instead of propagating.
     *
     * <p>Right after the read, every {@code finished} entry observed in {@code readyTasks} is
     * declined via {@link FinishedDecline#declineObserved} (design D4 of
     * enforce-finish-terminality) — best-effort per entry, so one failing decline is logged and
     * left for the next poll cycle rather than counting as an outage or aborting this poll.
     *
     * @throws InterruptedException if the feed thread is interrupted mid-outage-retry (SIGTERM
     *     shutdown stop signal, FR11) — see {@link FeedOutageRetry#run}
     */
    Poll poll(Instant now) throws InterruptedException {
        return outageRetry.run("feed poll", () -> {
            List<ReadyTask> readyTasks = tracker.listReady(FeedPolicy.FEED_LIMIT);
            FinishedDecline.declineObserved(tracker, readyTasks);
            int openFrontCount = tracker.listOpen().size();
            List<ReadyTask> candidates = FeedPolicy.selectClaimCandidates(
                    readyTasks, backoffBase, backoffCap, now, openFrontCount, wipLimit, random);
            return new Poll(readyTasks, openFrontCount, now, candidates);
        });
    }

    /**
     * Attempts to claim one of {@code candidates}, abandoning the acquired permit on a lost race
     * (design D5) or assigning and starting a slot on success — the fall-through-with-no-sleep
     * behavior both {@link FeedAutomaton#step()} and {@link FeedAutomaton#drain()} share for a
     * non-empty candidate list. Only the {@link #attemptClaim} tracker walk ({@code claim} plus the
     * per-candidate {@code listOpen} re-check) runs through {@link FeedOutageRetry} (NFR-R3), so a
     * sustained outage there retries with backoff instead of propagating. The permit accounting that
     * follows ({@code assign}/{@code abandon}, slot launch, log) is deliberately kept <em>outside</em>
     * the retry: those are deterministic in-process side effects, not tracker calls, so a bug in one
     * must surface as an exception rather than be misclassified as an outage and retried forever —
     * and, critically, a re-run of the claim walk after a permit was already assigned would see this
     * instance's own claim answer {@code Held}, drop through as a lost race, {@code abandon()} the
     * permit, and strand the tracker claim until its TTL/reaper.
     *
     * @throws InterruptedException if the feed thread is interrupted mid-outage-retry (SIGTERM
     *     shutdown stop signal, FR11) — see {@link FeedOutageRetry#run}
     */
    void claimOrAbandon(List<ReadyTask> candidates) throws InterruptedException {
        TaskRef claimed = outageRetry.run("feed claim", () -> attemptClaim(candidates));
        if (claimed == null) {
            slotLedger.abandon();
        } else {
            slotLedger.assign(claimed);
            startSlot(claimed);
            stateLogger.onSlotFilled(slotLedger.freeSlots(), wipLimit);
        }
    }

    private @Nullable TaskRef attemptClaim(List<ReadyTask> candidates) {
        for (ReadyTask candidate : candidates) {
            if (!OpenFrontGate.isStillEligible(
                    candidate, () -> tracker.listOpen().size(), wipLimit)) {
                continue;
            }
            ClaimResult claim = tracker.claim(candidate.ref(), instanceId.value());
            if (claim instanceof ClaimResult.Acquired) {
                return candidate.ref();
            }
            // Held: another instance won the race for this entry — fall through (FR9).
        }
        return null;
    }

    private void startSlot(TaskRef claimed) {
        Thread.ofVirtual().name("gnomish-slot-" + claimed.id()).start(() -> {
            try {
                slotRunner.run(claimed);
            } finally {
                slotLedger.release(claimed);
            }
        });
    }
}
