package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.FreshClaimBaseReport;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.baseref.UnderdeterminedCause;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How a fresh claim ends when {@link FreshClaimBaseBinding} could not bind its base (FR9, D9 of
 * add-base-ref-resolution): a base that cannot be determined, or a resolved ref whose refresh
 * refuses it, is a deterministic fact — the task parks {@code INFRA} with a report and the run
 * stops for a human; a configured remote that never answers is the daemon's infrastructure
 * condition — the claim is released with no penalty through {@link ClaimOutageRelease}. Each end
 * writes its one operator line here, the layer that made the final decision; the tracker write is
 * best effort and its failure is a second, distinct event.
 *
 * <p>Implements FR2, FR6, D6, D15 of add-base-ref-resolution.
 */
final class FreshClaimBaseFailures {

    private static final Logger log = LoggerFactory.getLogger(FreshClaimBaseFailures.class);

    private FreshClaimBaseFailures() {}

    static TakeResult parkUnderdetermined(
            TaskState finalState,
            TaskRef ref,
            Tracker tracker,
            UnderdeterminedCause cause,
            List<UntrustedText> values,
            String resolverAccount) {
        String report = FreshClaimBaseReport.underdetermined(ref.id(), cause, values, resolverAccount);
        // The resolver's sentence is its own prose, and the designator values it quotes entered it
        // through the carrier's toString() — the log exit — so it is inert with no call here.
        log.error(
                OperatorEvent.FRESH_CLAIM_BASE_UNDERDETERMINED.head()
                        + "parking task {}: its base could not be determined ({}): {}",
                ref.id(),
                cause,
                resolverAccount);
        parkBestEffort(tracker, ref, report);
        return new TakeResult.AwaitingHuman(finalState, ParkReason.INFRA, report);
    }

    static TakeResult parkRefused(
            TaskState finalState, TaskRef ref, Tracker tracker, String resolvedRef, UntrustedText refusal) {
        String fullReport = FreshClaimBaseReport.refused(ref.id(), resolvedRef, refusal);
        log.error(
                OperatorEvent.FRESH_CLAIM_BASE_REFUSED.head()
                        + "parking task {}: its resolved base ref '{}' could not be refreshed: {}",
                ref.id(),
                resolvedRef,
                refusal.forLog());
        parkBestEffort(tracker, ref, fullReport);
        return new TakeResult.AwaitingHuman(finalState, ParkReason.INFRA, fullReport);
    }

    private static void parkBestEffort(Tracker tracker, TaskRef ref, String report) {
        try {
            tracker.park(ref, ParkReason.INFRA, report);
        } catch (RuntimeException unparked) {
            log.error(
                    OperatorEvent.FRESH_CLAIM_BASE_PARK_FAILED.head()
                            + "park(INFRA) failed for task {} whose base could not be resolved/refreshed; stopping"
                            + " for a human anyway",
                    ref.id(),
                    unparked);
        }
    }

    static TakeResult release(TaskRef ref, Tracker tracker, String resolvedRef, UntrustedText reason) {
        log.warn(
                OperatorEvent.FRESH_CLAIM_BASE_REFRESH_UNAVAILABLE.head()
                        + "releasing claim on task {}: origin never answered the refresh of its resolved base ref"
                        + " '{}': {}",
                ref.id(),
                resolvedRef,
                reason.forLog());
        // The resolved ref is not untrusted text: it is what BaseRefResolver decided, and every tier
        // of that decision is held to RefNameSyntax (NG4).
        return ClaimOutageRelease.release(
                tracker,
                ref,
                "the refresh of its resolved base ref '" + resolvedRef + "'",
                reason,
                unreleased -> log.error(
                        OperatorEvent.FRESH_CLAIM_BASE_RELEASE_FAILED.head()
                                + "release failed for task {} whose base refresh could not reach origin",
                        ref.id(),
                        unreleased));
    }
}
