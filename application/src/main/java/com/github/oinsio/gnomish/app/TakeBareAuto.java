package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.FeedPolicy;
import com.github.oinsio.gnomish.app.take.FinishedDecline;
import com.github.oinsio.gnomish.app.take.TakeResult;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Random;

/**
 * Bare auto mode ({@code take} with no ref, FR10, NFR-C1, FR6/FR9 of add-factory-serve): reads the
 * ready queue via {@link Tracker#listReady} plus the open-front count, then hands both to {@link
 * BareTakeClaimWalk}, which filters abort backoff, prefers returned tasks over WIP-gated fresh ones,
 * head-zone-picks candidates (design D2, D4), and walks the ordered list claiming each — re-checking
 * the open-front gate per candidate (design D5) — until a claim succeeds and that one task is worked
 * to a terminal result, or every eligible entry has lost its claim race. This class is the entry
 * point and config plumbing; the walk itself lives in {@link BareTakeClaimWalk} for file size.
 *
 * <p>{@link FeedPolicy#FEED_LIMIT} caps how many head-of-queue entries {@code listReady} returns.
 * FR10 processes exactly one task per bare-take run, so in the overwhelmingly common case only the
 * first entry is ever inspected; the limit exists only to give the race-loss/backoff fallback loop
 * enough candidates to walk through without a second tracker round-trip — see the constant's own
 * Javadoc for the number's rationale. It is not a per-run task limit — FR10 always processes one.
 *
 * <p>The {@code taskId} MDC key (NFR-O1) is set only once a claim actually succeeds (in {@link
 * BareTakeClaimWalk}) — an empty or all-raced-away queue has no unique task to attribute the result
 * to, so no key is ever set on those paths; {@link TakeCommand#run} clears it unconditionally in its
 * own {@code finally} once this run returns.
 *
 * <p>Implements FR10, NFR-C1, NFR-O1 of add-tracker-port. Implements FR6, FR9, NFR-C1, D2, D4, D5
 * of add-factory-serve.
 */
public final class TakeBareAuto {

    private final BareTakeClaimWalk walk;

    /**
     * @param wiring the slot's equipment (D2 of introduce-slot-wiring), fixed for the whole take
     *     invocation; its MDC key is set to the claimed candidate's ref id the moment a claim
     *     actually succeeds (NFR-O1), matching {@link GitResumeRunner}'s own key; never null
     * @param backoffBase the abort-backoff base (design D10); never null
     * @param backoffCap the abort-backoff cap (design D10); never null
     * @param clock supplies "now" for the backoff filter; never null
     * @param wipLimit the configured WIP limit W (design D3 of add-factory-serve): fresh tasks are
     *     claimable only while the open-front count stays below it
     * @param random the source of randomness for {@link FeedPolicy}'s head-zone pick (design D4);
     *     never null — a seeded instance makes the pick deterministic for tests
     */
    TakeBareAuto(
            SlotWiring wiring, Duration backoffBase, Duration backoffCap, Clock clock, int wipLimit, Random random) {
        var claimAndWork = new TakeClaimAndWorkFactory(wiring).forSlot();
        this.walk = new BareTakeClaimWalk(
                claimAndWork, wiring.taskIdMdcKey(), backoffBase, backoffCap, clock, wipLimit, random);
    }

    /**
     * Runs one bare auto {@code take} attempt: reads the ready-queue snapshot, declines every
     * {@code finished} entry observed in it via {@link FinishedDecline#declineObserved} (design D4
     * of enforce-finish-terminality, best-effort per entry), then reads the open-front count and
     * delegates the candidate selection, walk, and claim to {@link BareTakeClaimWalk} (see class
     * javadoc).
     *
     * <p>Implements FR10, NFR-C1 of add-tracker-port. Implements FR6, FR9, NFR-C1, D2, D5 of
     * add-factory-serve. Implements FR3, FR4, NFR-R2, NFR-R3, NFR-O1 of enforce-finish-terminality.
     *
     * @param run the run order: the project clone (never mutated outside a task worktree), the
     *     loaded pipeline the run advances through, and which role(s), if any, use the interactive
     *     console adapter; never null
     * @param tracker the tracker port; never null
     * @param instanceId this factory instance's identity; never null
     * @return the {@link TakeResult} of the one task processed; {@link TakeResult.EmptyQueue} when
     *     the backoff-eligible queue was structurally empty; {@link TakeResult.Skipped} naming the
     *     WIP limit when only fresh WIP-blocked tasks remained; {@link TakeResult.Skipped} naming
     *     the claim race when every claim candidate lost its race
     */
    public TakeResult run(RunOrder run, Tracker tracker, InstanceId instanceId) {
        List<ReadyTask> readyTasks = tracker.listReady(FeedPolicy.FEED_LIMIT);
        // A one-shot run: its own latch, cold, discarded with the run (FR12).
        new FinishedDecline().declineObserved(tracker, readyTasks);
        int openFrontCount = tracker.listOpen().size();
        return walk.resolve(run, tracker, instanceId, readyTasks, openFrontCount);
    }
}
