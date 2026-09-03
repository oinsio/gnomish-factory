package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.BackoffPolicy;
import com.github.oinsio.gnomish.app.take.FeedPolicy;
import com.github.oinsio.gnomish.app.take.OpenFrontGate;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.status.AnchorLog;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.slf4j.MDC;

/**
 * The candidate-selection-and-claim heart of {@link TakeBareAuto} (FR6, FR9, NFR-C1, D2, D4, D5 of
 * add-factory-serve): given the ready-queue snapshot and open-front count, delegates to {@link
 * FeedPolicy} to filter abort backoff, prefer returned tasks, and head-zone-pick candidates (D2,
 * D4), then walks the ordered list attempting to claim each — re-checking the open-front gate per
 * candidate ({@link OpenFrontGate}, D5) — until either a claim succeeds (processing that one task to
 * its terminal result) or every eligible entry has lost its claim race. Extracted from {@link
 * TakeBareAuto} for file size; that class fetches the snapshot and delegates the decision here.
 *
 * <p>Implements FR10, NFR-C1, NFR-O1 of add-tracker-port. Implements FR6, FR9, NFR-C1, D2, D4, D5
 * of add-factory-serve.
 */
record BareTakeClaimWalk(
        TakeClaimAndWork claimAndWork,
        String taskIdMdcKey,
        Duration backoffBase,
        Duration backoffCap,
        Clock clock,
        int wipLimit,
        Random random) {

    /**
     * Selects claim candidates from {@code readyTasks} and walks them (see class javadoc): returns
     * the {@link TakeResult} of the one claimed-and-worked task, or a terminal empty/skip result
     * when no candidate could be claimed.
     */
    TakeResult resolve(
            Path cloneDir,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            Tracker tracker,
            InstanceId instanceId,
            List<ReadyTask> readyTasks,
            int openFrontCount) {
        List<ReadyTask> candidates = FeedPolicy.selectClaimCandidates(
                readyTasks, backoffBase, backoffCap, clock.instant(), openFrontCount, wipLimit, random);

        if (candidates.isEmpty()) {
            return emptyCandidatesResult(readyTasks, openFrontCount);
        }

        for (ReadyTask candidate : candidates) {
            if (!OpenFrontGate.isStillEligible(
                    candidate, () -> tracker.listOpen().size(), wipLimit)) {
                // The open-front count grew past wipLimit since the initial snapshot (design D5):
                // skip this fresh candidate without treating it as a claim-race loss.
                continue;
            }
            var claim = tracker.claim(candidate.ref(), instanceId.value());
            if (claim instanceof ClaimResult.Acquired) {
                // NFR-O1: only the candidate actually claimed gets the taskId MDC key — candidates
                // merely considered and lost to a race are never tagged, since this instance never
                // ends up acting on them.
                MDC.put(taskIdMdcKey, candidate.ref().id());
                // FR2 of harden-logging-observability: the same anchor form the serve feed emits,
                // from the second claim path. A bare take is a one-slot instance that this claim
                // has just filled, so it reports no free slot out of one — the identical fact the
                // feed states about its own ledger, not a placeholder.
                AnchorLog.claimAcquired(candidate.ref().id(), 0, 1);
                var trackerTask = tracker.fetchTask(candidate.ref());
                return claimAndWork.dispatchAfterClaim(
                        cloneDir, null, definition, interactiveMode, false, trackerTask, tracker, instanceId);
            }
            // Held: another instance won the race for this entry between the feed read and this
            // claim attempt — fall through to the next eligible candidate (see class javadoc).
        }
        return new TakeResult.Skipped(
                "every eligible task in the queue was already claimed by another instance — nothing to take"
                        + " this run");
    }

    /**
     * Distinguishes, up front from the {@link FeedPolicy} result, the two ways the candidate list
     * can come back empty (design D2): a structurally empty backoff-eligible queue ({@link
     * TakeResult.EmptyQueue}) versus backoff-eligible tasks that existed but were entirely fresh and
     * WIP-blocked ({@link TakeResult.Skipped} naming the WIP limit, FR6's "WIP limit blocks a fresh
     * start" scenario). The per-claim {@link OpenFrontGate} re-check inside the claim loop is a
     * narrower, later concern (bounding race overshoot, D5) and does not redefine this boundary.
     */
    private TakeResult emptyCandidatesResult(List<ReadyTask> readyTasks, int openFrontCount) {
        List<ReadyTask> backoffEligible =
                BackoffPolicy.filterEligible(readyTasks, backoffBase, backoffCap, clock.instant());
        if (backoffEligible.isEmpty()) {
            return new TakeResult.EmptyQueue();
        }
        return new TakeResult.Skipped("WIP limit reached: " + openFrontCount + " open front(s) at or above the"
                + " configured limit of " + wipLimit + " — " + backoffEligible.size()
                + " fresh task(s) waiting for a front to close; no returned tasks are ready");
    }
}
