package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.FeedPolicy;
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
final class FeedCycle {

    /** See {@code TakeBareAuto.FEED_LIMIT}: enough head-of-queue entries for the fallback walk. */
    static final int FEED_LIMIT = 20;

    /** One poll's raw feed plus the eligibility-filtered candidates. */
    record Poll(List<ReadyTask> readyTasks, int openFrontCount, Instant now, List<ReadyTask> candidates) {}

    private final Tracker tracker;
    private final InstanceId instanceId;
    private final SlotLedger slotLedger;
    private final SlotRunner slotRunner;
    private final Duration backoffBase;
    private final Duration backoffCap;
    private final int wipLimit;
    private final Random random;
    private final FeedStateLogger stateLogger;
    private final FeedOutageRetry outageRetry;

    FeedCycle(
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
        this.tracker = tracker;
        this.instanceId = instanceId;
        this.slotLedger = slotLedger;
        this.slotRunner = slotRunner;
        this.backoffBase = backoffBase;
        this.backoffCap = backoffCap;
        this.wipLimit = wipLimit;
        this.random = random;
        this.stateLogger = stateLogger;
        this.outageRetry = outageRetry;
    }

    /**
     * Polls the tracker once and applies the eligibility filter (design D2), at instant {@code
     * now}. The tracker reads run through {@link FeedOutageRetry} (NFR-R3): a sustained outage
     * retries with backoff instead of propagating.
     */
    Poll poll(Instant now) {
        return outageRetry.run("feed poll", () -> {
            List<ReadyTask> readyTasks = tracker.listReady(FEED_LIMIT);
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
     * non-empty candidate list. Runs through {@link FeedOutageRetry} (NFR-R3) exactly like {@link
     * #poll}, so a sustained outage hitting {@code claim} (or the per-candidate {@code listOpen}
     * re-check) retries with backoff instead of propagating.
     */
    void claimOrAbandon(List<ReadyTask> candidates) {
        outageRetry.run("feed claim", () -> {
            TaskRef claimed = attemptClaim(candidates);
            if (claimed == null) {
                slotLedger.abandon();
            } else {
                slotLedger.assign(claimed);
                startSlot(claimed);
                stateLogger.onSlotFilled(slotLedger.freeSlots(), wipLimit);
            }
            return null;
        });
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
