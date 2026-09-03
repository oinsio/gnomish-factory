package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.branch.BranchRecoveryFailedException;
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The crash arm of the infrastructure-abort protocol (FR14 "Runner crash is an abort", D3, D16):
 * turns an uncaught {@link RuntimeException} of the post-claim take run — a fresh-claim git
 * operation, a resume/salvage step, or a tracker write itself — into the very same best-effort
 * {@link AbortHandler} call an engine {@code Aborted} outcome makes. A runner crash and a broken
 * durability guarantee therefore release the claim and decide the K fuse identically, and the run
 * exits 12 or 13 rather than a bare 1 leaving a hanging {@code Working} claim.
 *
 * <p>Only genuinely unexpected failures reach here: deliberate, dedicated-exit-code control flow
 * ({@code UsageException} &rarr; 2) is rethrown by the
 * caller ({@code TakeClaimAndWork}) and never funnels into this protocol, since D16 keeps the exit
 * codes shared with {@code run} at their own meaning.
 *
 * <p>Abort facts are read best-effort: a dead tracker is itself a plausible crash cause and must
 * never turn the abort into a bare 1, so an unreadable fact set is treated as {@link
 * AbortFacts#none()} (the K fuse then counts this as the first abort in the streak); {@link
 * AbortHandler}'s own {@code recordAbort} is likewise best-effort (NFR-R2). The crash's own type
 * and message are carried into the abort {@code cause}, which {@link AbortHandler} logs at ERROR.
 *
 * <p>The attempt is categorized before it is spent (FR14, design D9 of harden-task-branch-contract):
 * a crash carrying a {@link BranchRecoveryFailedException} anywhere in its cause chain is a failed
 * branch repair, everything else is an instance crash. Both spend from the same counter and trip
 * the same threshold — the category only decides how the quarantine report reads.
 *
 * <p>Implements FR14, NFR-R2, D3, D16 of add-tracker-port; FR14 of harden-task-branch-contract.
 */
public final class TakeCrashAbort {

    private static final Logger log = LoggerFactory.getLogger(TakeCrashAbort.class);

    private final AbortHandler abortHandler;
    private final int abortThreshold;

    /**
     * @param abortHandler the shared best-effort abort protocol (same instance the engine {@code
     *     Aborted} path uses); never null
     * @param abortThreshold the configured abort-fuse threshold (K) passed through to {@code
     *     abortHandler}; positive
     */
    public TakeCrashAbort(AbortHandler abortHandler, int abortThreshold) {
        this.abortHandler = abortHandler;
        this.abortThreshold = abortThreshold;
    }

    /**
     * Runs the best-effort abort protocol for the uncaught {@code crash} of a post-claim take run,
     * returning the {@link TakeResult} its outcome maps to: {@link TakeResult.Aborted} below the
     * fuse, or {@link TakeResult.AwaitingHuman} with {@link ParkReason#INFRA} at it.
     *
     * <p>Implements FR14, D3, D16 of add-tracker-port.
     *
     * @param definition the running pipeline; its first stage names the last structurally-known
     *     position reported in the aborted result's final state — a crash has no live engine state
     *     to carry; never null
     * @param trackerTask the claimed task the run crashed on; never null
     * @param tracker the tracker port, for the best-effort abort-facts read and the abort write;
     *     never null
     * @param instanceId this factory instance's identity; never null
     * @param crash the uncaught exception the post-claim run died with; never null
     * @return the abort protocol's terminal {@link TakeResult}
     */
    public TakeResult onCrash(
            PipelineDefinition definition,
            TrackerTask trackerTask,
            Tracker tracker,
            InstanceId instanceId,
            RuntimeException crash) {
        TaskRef ref = trackerTask.ref();
        String cause = "uncaught exception during the take run: " + crash;
        AbortFacts facts = abortFactsBestEffort(tracker, ref);
        TaskState finalState =
                TaskState.atStageStart(definition.stages().getFirst().name());
        return abortHandler.handle(ref, finalState, cause, facts, abortThreshold, instanceId, categoryOf(crash));
    }

    /**
     * Which category of the unified accounting this crash spends: a failed repair of a non-clean
     * branch shape names itself on the way up ({@link BranchRecoveryFailedException}), possibly
     * wrapped by a layer above it, so the whole cause chain is searched; anything else is an
     * instance crash (FR14 of harden-task-branch-contract).
     */
    private static RecoveryCause categoryOf(RuntimeException crash) {
        for (Throwable link = crash; link != null; link = link.getCause()) {
            if (link instanceof BranchRecoveryFailedException) {
                return RecoveryCause.RECOVERY_FAILURE;
            }
        }
        return RecoveryCause.INSTANCE_CRASH;
    }

    /**
     * Reads {@code ref}'s abort facts, defaulting to {@link AbortFacts#none()} if the tracker read
     * itself throws (NFR-R2): with no facts to read — e.g. the tracker is the thing that crashed —
     * the K fuse treats this as the first abort in the streak, and {@link AbortHandler}'s own
     * best-effort {@code recordAbort} still yields an {@code Aborted} result on a fully dead
     * tracker rather than a bare 1.
     */
    private static AbortFacts abortFactsBestEffort(Tracker tracker, TaskRef ref) {
        try {
            return tracker.fetchTask(ref).abortFacts();
        } catch (RuntimeException unreadable) {
            // Loud on the way past (NFR-O1): the degrade RESETS the streak this crash is counted
            // into, so a tracker that is merely flaky — readable when the fuse is written, not when
            // it is read — keeps every crash looking like the first one and the K fuse never trips.
            // An operator seeing repeated aborts on one task needs this line to tell "K is too high"
            // from "the count never accumulated".
            log.warn(
                    OperatorEvent.ABORT_FACTS_UNREADABLE.head()
                            + "abort facts unreadable for task {}; counting this crash as the first abort in the streak",
                    ref.id(),
                    unreadable);
            return AbortFacts.none();
        }
    }
}
