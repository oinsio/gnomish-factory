package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.branch.BranchQuarantineException;
import com.github.oinsio.gnomish.app.port.git.BoxGitVersionRefusedException;
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException;
import com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException;
import com.github.oinsio.gnomish.app.port.git.DivergedBranchException;
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeUnavailableException;
import com.github.oinsio.gnomish.app.port.run.ImageContractRefusedException;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import com.github.oinsio.gnomish.app.take.TakeCrashAbort;
import com.github.oinsio.gnomish.app.take.TakeQuarantinePark;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides where an uncaught {@link RuntimeException} of a claimed run goes — the classification
 * of a post-claim failure into its one terminal path. {@link TakeClaimAndWork} holds the claim, the
 * heartbeat and the tenure around the run and hands every failure of that run here; this class owns
 * the mapping, holding the crash arm as a field built once per slot. Extracted out of {@link
 * TakeClaimAndWork} by finding F56 of tighten-spec-contract.
 *
 * <p>Five arms, checked in this order:
 *
 * <ol>
 *   <li><b>An operator precondition</b> — the factory clone's ref format ({@link
 *       CloneRefFormatRefusedException}), the container runtime ({@link
 *       ContainerRuntimeUnavailableException}), the sandbox image's git floor ({@link
 *       BoxGitVersionRefusedException}) or its image contract ({@link
 *       ImageContractRefusedException}), as {@link OperatorPreconditionRefusals} decides — ends {@link TakeResult.OperatorPreconditionUnmet} after
 *       the claim is released through the outage release's one owner, {@link ClaimOutageRelease}
 *       (design D18 of add-sandbox-base-image). Each is the operator's to fix, not the task's
 *       fault, so it spends no abort, backoff or fuse; a returned result is what every sink of a
 *       slot reads, where a rethrow reached only the one caller with a {@code catch}. The command
 *       or the daemon condition the result ends writes the operator's line; this layer logs the
 *       classification at DEBUG (design D19, "One failure, one log").
 *   <li><b>Deliberate, dedicated-exit-code control flow</b> is rethrown unchanged: {@link
 *       UsageException} keeps exit 2 and {@link DivergedBranchException} keeps exit 5 (D16: codes
 *       shared with {@code run} keep their meaning). These two alone rethrow, because their codes
 *       exist and a caller reads them. The latter reaches here only when the tenure was dropped
 *       mid-run — a beat reported the claim gone — so the diverged branch is no longer this
 *       instance's to repair (FR8 of harden-task-branch-contract). The claim is still dropped
 *       first (see {@link #releaseBestEffort}), so a refusal never leaves the task hanging {@code
 *       Working}.
 *   <li><b>A quarantined branch</b> — one that classifies to a non-recoverable shape — stops at
 *       {@link TakeQuarantinePark}, parking the task for a human on the first classification and
 *       spending no attempt of the unified recovery accounting (FR15 of
 *       harden-task-branch-contract).
 *   <li><b>A task-branch lookup that could not reach origin</b> ({@link
 *       BranchLocationUnavailableException}) is a claim-time fetch outage: {@link
 *       TaskBranchLookupOutage} releases the claim plain, outside the abort accounting, exactly as a
 *       base-refresh outage is (FR9 of add-base-ref-resolution, finding F6 of
 *       tighten-spec-contract). Classified by its cause, whichever step met it — the router's
 *       classification or the resume bootstrap's own lookup — never by the step.
 *   <li><b>Anything else</b> is a runner crash of a claimed run and funnels into the best-effort
 *       abort protocol via {@link TakeCrashAbort} (FR14 "Runner crash is an abort", D16 "an
 *       uncaught exception runs the abort protocol and exits 12 or 13, never a bare 1").
 * </ol>
 *
 * <p>Implements FR14, D16 of add-tracker-port; FR15 of harden-task-branch-contract; FR9 of
 * add-base-ref-resolution; FR5, FR14, NFR-O3 of add-sandbox-base-image.
 */
final class ClaimedRunFailureDispatch {

    private static final Logger log = LoggerFactory.getLogger(ClaimedRunFailureDispatch.class);

    private final TakeCrashAbort crashAbort;

    /**
     * @param crashAbort the slot's crash arm of the abort protocol; never null
     */
    ClaimedRunFailureDispatch(TakeCrashAbort crashAbort) {
        this.crashAbort = crashAbort;
    }

    /**
     * Maps the uncaught {@code failure} of the claimed run of {@code order} to its terminal result,
     * or rethrows it after dropping the claim when it is deliberate control flow (class javadoc).
     *
     * @param order the take order whose claimed run failed; never null
     * @param failure what the run threw; never null
     * @return the terminal result of the arm the failure classifies to
     */
    TakeResult onFailure(TakeOrder order, RuntimeException failure) {
        Optional<DaemonConditionKind> precondition = OperatorPreconditionRefusals.kindOf(failure);
        if (precondition.isPresent()) {
            return preconditionUnmet(order, precondition.get(), failure);
        }
        return switch (failure) {
            case UsageException deliberate -> throw releaseBestEffort(order, deliberate);
            case DivergedBranchException deliberate -> throw releaseBestEffort(order, deliberate);
            case BranchQuarantineException quarantine -> TakeQuarantinePark.onQuarantine(order, quarantine);
            case BranchLocationUnavailableException outage ->
                TaskBranchLookupOutage.release(order.tracker(), order.ref(), outage);
            default -> crashAbort.onCrash(order, failure);
        };
    }

    /**
     * Ends the claimed run of {@code order} on the operator precondition {@code refused} met: the
     * claim released plain, the typed result returned (design D18 of add-sandbox-base-image).
     */
    private static TakeResult preconditionUnmet(TakeOrder order, DaemonConditionKind kind, RuntimeException refused) {
        // The refusal's message is the operator's line, written by the deciding layer the result
        // ends (the command, or the daemon condition); here it is the classification only.
        log.debug(
                "task {}: operator precondition {} refused the claimed run",
                order.ref().id(),
                kind);
        return ClaimOutageRelease.refused(
                order.tracker(),
                order.ref(),
                kind,
                String.valueOf(refused.getMessage()),
                unreleased -> log.error(
                        OperatorEvent.PRECONDITION_CLAIM_RELEASE_FAILED.head()
                                + "release failed for task {} whose claimed run met operator precondition {}",
                        order.ref().id(),
                        kind,
                        unreleased));
    }

    /**
     * Drops this instance's claim on the order's task before a deliberate, dedicated-exit-code
     * failure ends the invocation, and returns that failure for the caller to rethrow. The exit code
     * is the whole point of rethrowing those two unchanged — but the process is leaving with no
     * further tracker write for this ref, so without this the task sits {@code Working} behind a
     * runner that is already gone until its lease expires, exactly the hanging claim the crash arm
     * ({@link TakeCrashAbort}) exists to prevent. {@link Tracker#release} is the right verb rather
     * than {@code recordAbort} or {@code park}: nothing infrastructural failed, so the task's
     * logical state is left untouched. The release does not return the task to {@code Ready} by
     * itself — the working label stays and the reaper of the {@code claim-heartbeat} capability
     * restores the queue once the claim is stale; what the release buys is the heartbeat stopping
     * cleanly so that staleness is reached, not a shorter wait. The fenced immediate return is
     * {@code add-claim-return}'s verb.
     *
     * <p>Best-effort, in the same shape as {@link com.github.oinsio.gnomish.app.take.AbortHandler}'s
     * tracker writes (NFR-R2): an unreachable tracker is itself a plausible reason a run is bailing
     * out, and it must not replace the operator-facing usage error with a tracker stack trace — the
     * failed release is logged as suppressed on the original exception instead.
     */
    private static RuntimeException releaseBestEffort(TakeOrder order, RuntimeException deliberate) {
        try {
            order.tracker().release(order.ref());
        } catch (RuntimeException unreleased) {
            deliberate.addSuppressed(unreleased);
        }
        return deliberate;
    }
}
