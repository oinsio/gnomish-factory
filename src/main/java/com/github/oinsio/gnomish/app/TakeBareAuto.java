package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.ClaimBeat;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.BackoffPolicy;
import com.github.oinsio.gnomish.app.take.FeedPolicy;
import com.github.oinsio.gnomish.app.take.OpenFrontGate;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import org.slf4j.MDC;

/**
 * Bare auto mode ({@code take} with no ref, FR10, NFR-C1, FR6/FR9 of add-factory-serve): fetches
 * the ready queue via {@link Tracker#listReady}, delegates to the shared {@link FeedPolicy}
 * (design D2) to filter abort backoff ({@link BackoffPolicy#filterEligible}), prefer returned
 * tasks over WIP-gated fresh ones, and head-zone-pick the first claim candidate (design D4), then
 * walks the ordered candidate list attempting to claim each one — re-checking the open-front gate
 * per candidate ({@link OpenFrontGate}, design D5) — until either a claim succeeds — processing
 * that one task to its terminal result and returning — or every eligible entry has lost its claim
 * race.
 *
 * <p>{@link #FEED_LIMIT} caps how many head-of-queue entries {@code listReady} returns. FR10
 * processes exactly one task per bare-take run, so in the overwhelmingly common case only the
 * first entry is ever inspected; the limit exists only to give the race-loss/backoff fallback loop
 * enough candidates to walk through without a second tracker round-trip. No spec or design fixes a
 * number, so a small constant is chosen: 20 comfortably covers "several tasks concurrently backed
 * off or raced" without over-fetching from the adapter on every run.
 *
 * <p>A claim race loss (adapter reports {@code Held}) is NOT an operator-facing refusal the way
 * explicit mode's held-task disposition is (design D3): {@code listReady} already reports only
 * unclaimed tasks, so a race loss here means a concurrent instance won the same entry between the
 * feed read and this instance's claim attempt — genuinely rare, invisible plumbing that simply
 * tries the next eligible entry. A candidate the per-claim {@link OpenFrontGate} re-check rejects
 * (the open-front count grew past {@code wipLimit} since the initial snapshot, design D5) is
 * likewise skipped without being treated as a claim-race loss.
 *
 * <p>The {@code taskId} MDC key (NFR-O1) is set only once a claim actually succeeds — an empty
 * queue or an all-raced-away queue has no unique task to attribute the result to, so no key is
 * ever set on those paths; {@link TakeCommand#run} clears it unconditionally in its own
 * {@code finally} once this run returns.
 *
 * <p>Implements FR10, NFR-C1, NFR-O1 of add-tracker-port. Implements FR6, FR9, D2, D4, D5 of
 * add-factory-serve.
 */
public final class TakeBareAuto {

    /** See class javadoc for rationale; not a per-run task limit — FR10 always processes one. */
    static final int FEED_LIMIT = 20;

    private final TakeClaimAndWork claimAndWork;
    private final String taskIdMdcKey;
    private final Duration backoffBase;
    private final Duration backoffCap;
    private final Clock clock;
    private final int wipLimit;
    private final Random random;

    /**
     * @param assembly the shared engine/ports assembly, reused from the manual-run path; never null
     * @param worktreesRoot the root directory under which {@code <project-name>/<taskId>/}
     *     worktrees are created (design D6); never null
     * @param abortHandler the infrastructure-abort protocol (task 5.3); never null
     * @param abortThreshold the configured abort-fuse threshold (K) passed through to {@code
     *     abortHandler}; positive
     * @param taskIdMdcKey the MDC key this class sets to the claimed candidate's ref id the moment
     *     a claim actually succeeds (NFR-O1), and that deeper resume bootstrap steps also set,
     *     matching {@link GitResumeRunner}'s own key
     * @param backoffBase the abort-backoff base (design D10); never null
     * @param backoffCap the abort-backoff cap (design D10); never null
     * @param clock supplies "now" for the backoff filter; never null — mirrors {@link
     *     AbortHandler}'s own {@code Clock} collaborator for testability
     * @param credentialEnvVarsToScrub the active tracker adapter's declared credential
     *     environment variable names (design D17, NFR-S1 of add-tracker-port), threaded down to
     *     every {@link TakeEngineExecution} this class eventually constructs; never null
     * @param heartbeat the instance heartbeat lifecycle registered/unregistered around the claimed
     *     run (task 6.1 of add-claim-heartbeat, FR1); {@link ClaimBeat#NONE} when no beat runs
     * @param claimLossFlag the per-run heartbeat claim-loss flag (task 6.3, FR8 of
     *     add-claim-heartbeat), threaded down to every {@link TakeEngineExecution} this class
     *     constructs so the round boundary reacts to a beat-detected loss as a revocation; never null
     * @param wipLimit the configured WIP limit W (design D3 of add-factory-serve); fresh tasks are
     *     claimable only while the open-front count stays below it — see {@link FeedPolicy} and
     *     {@link OpenFrontGate}
     * @param random the source of randomness for {@link FeedPolicy}'s head-zone pick (design D4);
     *     never null — a seeded instance makes the pick deterministic for tests
     */
    TakeBareAuto(
            ManualRunAssembly assembly,
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
            Random random) {
        var resumeRunner = new TakeResumeRunner(
                assembly,
                worktreesRoot,
                taskIdMdcKey,
                abortHandler,
                abortThreshold,
                credentialEnvVarsToScrub,
                claimLossFlag);
        var dispositionResume =
                new TakeDispositionResume(resumeRunner, new TakeDecisionResume(resumeRunner), worktreesRoot);
        this.claimAndWork = new TakeClaimAndWork(
                assembly,
                worktreesRoot,
                abortHandler,
                abortThreshold,
                credentialEnvVarsToScrub,
                dispositionResume,
                heartbeat,
                claimLossFlag);
        this.taskIdMdcKey = taskIdMdcKey;
        this.backoffBase = backoffBase;
        this.backoffCap = backoffCap;
        this.clock = clock;
        this.wipLimit = wipLimit;
        this.random = random;
    }

    /**
     * The heartbeat-free construction used where no beat runs (the bare-auto unit spec): delegates
     * with {@link ClaimBeat#NONE} and a fresh empty {@link ClaimLossFlag} that never trips, so
     * call sites unconcerned with task 6.1's and 6.3's added seams don't need to supply them.
     */
    TakeBareAuto(
            ManualRunAssembly assembly,
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
                random);
    }

    /**
     * Runs one bare auto {@code take} attempt (see class javadoc).
     *
     * <p>Implements FR10, NFR-C1 of add-tracker-port. Implements FR6, FR9, D2, D5 of
     * add-factory-serve.
     *
     * @param cloneDir the project clone; never mutated outside a task worktree
     * @param definition the loaded pipeline the run advances through; never null
     * @param interactiveMode which role(s), if any, use the interactive console adapter
     * @param tracker the tracker port; never null
     * @param instanceId this factory instance's identity; never null
     * @return the {@link TakeResult} of the one task processed; {@link TakeResult.EmptyQueue} when
     *     the backoff-eligible queue was structurally empty; {@link TakeResult.Skipped} — naming
     *     the WIP limit — when backoff-eligible tasks existed but every one was fresh and blocked
     *     by the WIP limit; {@link TakeResult.Skipped} — naming the claim race — when claim
     *     candidates existed but every one of them lost its claim race (or was excluded by the
     *     per-claim open-front re-check)
     */
    public TakeResult run(
            Path cloneDir,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            Tracker tracker,
            InstanceId instanceId) {
        List<ReadyTask> readyTasks = tracker.listReady(FEED_LIMIT);
        int openFrontCount = tracker.listOpen().size();
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
     * TakeResult.EmptyQueue}, unchanged from before this task) versus backoff-eligible tasks that
     * existed but were entirely fresh and WIP-blocked ({@link TakeResult.Skipped} naming the WIP
     * limit, FR6's "WIP limit blocks a fresh start" scenario). The per-claim {@link OpenFrontGate}
     * re-check inside the claim loop is a narrower, later concern (bounding race overshoot, D5) and
     * does not redefine this up-front boundary.
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
