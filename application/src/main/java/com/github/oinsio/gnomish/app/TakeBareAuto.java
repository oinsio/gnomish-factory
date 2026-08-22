package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.ClaimBeat;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.FeedPolicy;
import com.github.oinsio.gnomish.app.take.FinishedDecline;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
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
     * @param assembly the shared engine/ports assembly, reused from the manual-run path; never null
     * @param git the task-git capability set every claim/resume path's store, branch and worktree
     *     operations come from; never null
     * @param worktreesRoot the root directory under which {@code <project-name>/<taskId>/}
     *     worktrees are created (design D6); never null
     * @param abortHandler the infrastructure-abort protocol (task 5.3); never null
     * @param abortThreshold the configured abort-fuse threshold (K) passed through to {@code
     *     abortHandler}; positive
     * @param taskIdMdcKey the MDC key set to the claimed candidate's ref id the moment a claim
     *     actually succeeds (NFR-O1), matching {@link GitResumeRunner}'s own key
     * @param backoffBase the abort-backoff base (design D10); never null
     * @param backoffCap the abort-backoff cap (design D10); never null
     * @param clock supplies "now" for the backoff filter; never null
     * @param credentialEnvVarsToScrub the active tracker adapter's declared credential
     *     environment variable names (design D17, NFR-S1 of add-tracker-port); never null
     * @param heartbeat the instance heartbeat lifecycle registered/unregistered around the claimed
     *     run (task 6.1 of add-claim-heartbeat, FR1); {@link ClaimBeat#NONE} when no beat runs
     * @param claimLossFlag the per-run heartbeat claim-loss flag (task 6.3, FR8 of
     *     add-claim-heartbeat); never null
     * @param wipLimit the configured WIP limit W (design D3 of add-factory-serve): fresh tasks are
     *     claimable only while the open-front count stays below it
     * @param random the source of randomness for {@link FeedPolicy}'s head-zone pick (design D4);
     *     never null — a seeded instance makes the pick deterministic for tests
     */
    TakeBareAuto(
            RunAssembly assembly,
            TaskGit git,
            Path worktreesRoot,
            AbortHandler abortHandler,
            int abortThreshold,
            String taskIdMdcKey,
            Duration backoffBase,
            Duration backoffCap,
            Clock clock,
            List<String> credentialEnvVarsToScrub,
            ClaimBeat heartbeat,
            ClaimLossFlag claimLossFlag,
            int wipLimit,
            Random random,
            ContainerTakeSupport containerTakeSupport) {
        var claimAndWork = TakeClaimAndWorkFactory.forSlot(
                assembly,
                git,
                worktreesRoot,
                taskIdMdcKey,
                abortHandler,
                abortThreshold,
                credentialEnvVarsToScrub,
                heartbeat,
                claimLossFlag,
                containerTakeSupport);
        this.walk = new BareTakeClaimWalk(claimAndWork, taskIdMdcKey, backoffBase, backoffCap, clock, wipLimit, random);
    }

    /**
     * The heartbeat-free construction used where no beat runs (the bare-auto unit spec): delegates
     * with {@link ClaimBeat#NONE} and a fresh empty {@link ClaimLossFlag} that never trips, so call
     * sites unconcerned with task 6.1's and 6.3's added seams don't need to supply them.
     */
    TakeBareAuto(
            RunAssembly assembly,
            TaskGit git,
            Path worktreesRoot,
            AbortHandler abortHandler,
            int abortThreshold,
            String taskIdMdcKey,
            Duration backoffBase,
            Duration backoffCap,
            Clock clock,
            List<String> credentialEnvVarsToScrub,
            int wipLimit,
            Random random) {
        this(
                assembly,
                git,
                worktreesRoot,
                abortHandler,
                abortThreshold,
                taskIdMdcKey,
                backoffBase,
                backoffCap,
                clock,
                credentialEnvVarsToScrub,
                ClaimBeat.NONE,
                new ClaimLossFlag(),
                wipLimit,
                random,
                ContainerTakeSupport.hostOnly());
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
     * @param cloneDir the project clone; never mutated outside a task worktree
     * @param definition the loaded pipeline the run advances through; never null
     * @param interactiveMode which role(s), if any, use the interactive console adapter
     * @param tracker the tracker port; never null
     * @param instanceId this factory instance's identity; never null
     * @return the {@link TakeResult} of the one task processed; {@link TakeResult.EmptyQueue} when
     *     the backoff-eligible queue was structurally empty; {@link TakeResult.Skipped} naming the
     *     WIP limit when only fresh WIP-blocked tasks remained; {@link TakeResult.Skipped} naming
     *     the claim race when every claim candidate lost its race
     */
    public TakeResult run(
            Path cloneDir,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            Tracker tracker,
            InstanceId instanceId) {
        List<ReadyTask> readyTasks = tracker.listReady(FeedPolicy.FEED_LIMIT);
        FinishedDecline.declineObserved(tracker, readyTasks);
        int openFrontCount = tracker.listOpen().size();
        return walk.resolve(cloneDir, definition, interactiveMode, tracker, instanceId, readyTasks, openFrontCount);
    }
}
