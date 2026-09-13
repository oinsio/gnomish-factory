package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.base.BaseDesignatorMapping;
import com.github.oinsio.gnomish.app.port.git.BasePin;
import com.github.oinsio.gnomish.app.port.git.BaseRefGit;
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.take.FreshClaimBaseReport;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.baseref.BaseDecision;
import com.github.oinsio.gnomish.baseref.BaseDefinition;
import com.github.oinsio.gnomish.baseref.BaseDesignator;
import com.github.oinsio.gnomish.baseref.BaseRefRequest;
import com.github.oinsio.gnomish.baseref.BaseRefResolver;
import com.github.oinsio.gnomish.baseref.BaseResolution;
import com.github.oinsio.gnomish.baseref.ResolutionMode;
import com.github.oinsio.gnomish.baseref.UnderdeterminedCause;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The fresh-claim base resolve-then-refresh step (FR2, FR6, D6, D15 of add-base-ref-resolution):
 * the one step both ends of the fresh-claim pair ({@link TakeFreshClaim}, {@link
 * TakeContainerFreshClaim}) share, so the policy lives once. Runs {@link BaseRefResolver} in
 * {@link ResolutionMode#AUTONOMOUS} — never {@code MANUAL}, which would wrongly let a fresh claim
 * fall through to the clone's local {@code HEAD} — over the trusted tier bound once at startup
 * ({@link TrustedBaseContext}, never re-read here), then narrow-fetches exactly the resolved ref
 * via {@link BaseRefGit#refresh} before anything durable is cut from it.
 *
 * <p>Mirrors {@link ResumeLawBinding}'s shape and its {@code bind}/{@code resolve} split — a
 * sealed {@link Outcome}, and a {@link #resolve} routing helper that hands the bound law to a
 * continuation so neither {@link TakeFreshClaim} nor {@link TakeContainerFreshClaim} spells the
 * three-way switch itself. Classified the same way {@link ResumeLawBinding} and {@link
 * TaskTierLaw} classify their own base/law failures (FR9): a resolution that cannot name a base at
 * all, or a refresh that refuses a resolved ref, is a deterministic fact — the task parks with a
 * report, no stage attempt burned; a configured remote that never answers is the daemon's
 * infrastructure condition, so the claim is released with no penalty (D9). An {@code
 * UnderdeterminedCause.NO_DEFAULT_BRANCH} refusal is structurally unreachable from this call site
 * — {@link TrustedBaseContext#defaultBranch()} is a non-null {@link
 * com.github.oinsio.gnomish.baseref.DefaultBranch}, established once at startup ({@link
 * TrustedTierStartup}) from the one place the remote is asked — but is still routed through the
 * same park rather than assumed away, so a future caller that ever did pass none fails closed
 * instead of silently falling through to a local checkout.
 *
 * <p>Implements FR2, FR6, D6, D15 of add-base-ref-resolution.
 */
final class FreshClaimBaseBinding {

    private static final Logger log = LoggerFactory.getLogger(FreshClaimBaseBinding.class);

    private FreshClaimBaseBinding() {}

    /** What the resolve+refresh step decided: the law to run under, or the terminal result that ends the claim. */
    sealed interface Outcome permits Bound, Parked, Released {}

    /**
     * The base resolved and its ref refreshed fresh from origin.
     *
     * @param lawBinding the binding the task's law and task tier are both read from — the
     *     refreshed commit, so law and pin name one SHA by construction
     * @param pin the durable base pin the caller records beside the start commit: the resolved ref
     *     name, the namespace origin held it in as the refresh established it, and the tier that
     *     produced the name. The kind is carried rather than discarded (D7 of
     *     add-base-ref-resolution, revised 2026-09-10) so a later resume fetches that namespace
     *     only instead of re-classifying a name that may since have been reused
     */
    record Bound(LawBinding lawBinding, BasePin pin) implements Outcome {}

    /**
     * No base could be determined, or a resolved ref's refresh refused it; the task was parked.
     *
     * @param result the terminal result carrying the report
     */
    record Parked(TakeResult result) implements Outcome {}

    /**
     * A configured remote never answered the refresh and the claim was released.
     *
     * @param result the terminal result the claim ends with
     */
    record Released(TakeResult result) implements Outcome {}

    /**
     * Everything one resolve+refresh needs beyond the mechanical claim context ({@code
     * cloneDir}/{@code finalState}/{@code tracker}), bundled so {@link #bind} and {@link #resolve}
     * stay under the project's 7-parameter limit.
     *
     * @param explicitBase the operator's {@code --base} override, or null; wins outright
     * @param trackerTask the claimed task, whose designator and ref identity this reads
     * @param trustedBase the trusted tier bound once at startup; never re-read here
     */
    record Request(@Nullable String explicitBase, TrackerTask trackerTask, TrustedBaseContext trustedBase) {}

    /**
     * Resolves the task's base and refreshes it, deciding the outcome.
     *
     * @param baseRefGit the base-ref capability the refresh reads through; never null
     * @param cloneDir the factory clone the refresh's narrow fetch lands in; never null
     * @param request the resolution's own inputs (see {@link Request}); never null
     * @param finalState the state a park reports — a fresh claim never entered a stage, so this is
     *     always the pipeline's first-stage start; never null
     * @param tracker the tracker port for the best-effort park/release; never null
     * @return the bound law, the park result, or the release result; never null
     */
    static Outcome bind(BaseRefGit baseRefGit, Path cloneDir, Request request, TaskState finalState, Tracker tracker) {
        TrackerTask trackerTask = request.trackerTask();
        TaskRef ref = trackerTask.ref();
        BaseDesignator designator = BaseDesignatorMapping.baseOf(trackerTask.designators());
        BaseDefinition base = request.trustedBase().base();
        BaseRefRequest baseRequest = new BaseRefRequest(
                request.explicitBase(),
                designator,
                base.allowedBases(),
                base.defaultRef(),
                ResolutionMode.AUTONOMOUS,
                request.trustedBase().defaultBranch());
        return switch (BaseRefResolver.resolve(baseRequest)) {
            case BaseResolution.Resolved(BaseDecision decision) ->
                refresh(baseRefGit, cloneDir, decision, finalState, ref, tracker);
            case BaseResolution.Underdetermined(var cause, var values, var reason) ->
                new Parked(parkUnderdetermined(finalState, ref, tracker, cause, values, reason));
        };
    }

    /**
     * {@link #bind} plus the routing both {@link TakeFreshClaim} and {@link TakeContainerFreshClaim}
     * need from it: a park/release result short-circuits, a bound law is handed to {@code
     * continuation} to build and run the engine.
     */
    static TakeResult resolve(
            BaseRefGit baseRefGit,
            Path cloneDir,
            Request request,
            TaskState finalState,
            Tracker tracker,
            Function<Bound, TakeResult> continuation) {
        return switch (bind(baseRefGit, cloneDir, request, finalState, tracker)) {
            case Parked(TakeResult parked) -> parked;
            case Released(TakeResult released) -> released;
            case Bound bound -> continuation.apply(bound);
        };
    }

    private static Outcome refresh(
            BaseRefGit baseRefGit,
            Path cloneDir,
            BaseDecision decision,
            TaskState finalState,
            TaskRef ref,
            Tracker tracker) {
        return switch (baseRefGit.refresh(cloneDir, decision.ref())) {
            case BaseRefreshOutcome.Refreshed(var ignoredRef, String commit, var kind, var _) ->
                new Bound(LawBinding.atRevision(cloneDir, commit), new BasePin(decision.ref(), kind, decision.rule()));
            case BaseRefreshOutcome.Refused(String report) ->
                new Parked(parkRefused(finalState, ref, tracker, decision.ref(), report));
            case BaseRefreshOutcome.Unavailable(String reason) ->
                new Released(release(ref, tracker, decision.ref(), reason));
        };
    }

    private static TakeResult parkUnderdetermined(
            TaskState finalState,
            TaskRef ref,
            Tracker tracker,
            UnderdeterminedCause cause,
            List<String> values,
            String reason) {
        String report = FreshClaimBaseReport.underdetermined(ref.id(), cause, values, reason);
        log.error(
                OperatorEvent.FRESH_CLAIM_BASE_UNDERDETERMINED.head()
                        + "parking task {}: its base could not be determined ({}): {}",
                ref.id(),
                cause,
                LogText.forLog(reason));
        parkBestEffort(tracker, ref, report);
        return new TakeResult.AwaitingHuman(finalState, ParkReason.INFRA, report);
    }

    private static TakeResult parkRefused(
            TaskState finalState, TaskRef ref, Tracker tracker, String resolvedRef, String report) {
        String fullReport = FreshClaimBaseReport.refused(ref.id(), resolvedRef, report);
        log.error(
                OperatorEvent.FRESH_CLAIM_BASE_REFUSED.head()
                        + "parking task {}: its resolved base ref '{}' could not be refreshed: {}",
                ref.id(),
                LogText.forLog(resolvedRef),
                LogText.forLog(report));
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

    private static TakeResult release(TaskRef ref, Tracker tracker, String resolvedRef, String reason) {
        log.warn(
                OperatorEvent.FRESH_CLAIM_BASE_REFRESH_UNAVAILABLE.head()
                        + "releasing claim on task {}: origin never answered the refresh of its resolved base ref"
                        + " '{}': {}",
                ref.id(),
                LogText.forLog(resolvedRef),
                LogText.forLog(reason));
        releaseBestEffort(tracker, ref);
        // Sanitized here, not at the sinks: this text becomes TakeResult.InfrastructureUnavailable's
        // reason, which SlotOutcomeLog and the drain/batch summaries log whole — by then no
        // untrusted accessor is left for UntrustedLogTextGateSpec to see (FR6 of
        // harden-logging-observability).
        return new TakeResult.InfrastructureUnavailable("Task " + ref.id() + " claim released (the reaper returns"
                + " it to Ready after the claim TTL): origin did not answer the refresh of its resolved base ref '"
                + LogText.forLog(resolvedRef) + "': "
                + LogText.forLog(reason));
    }

    private static void releaseBestEffort(Tracker tracker, TaskRef ref) {
        try {
            tracker.release(ref);
        } catch (RuntimeException unreleased) {
            log.error(
                    OperatorEvent.FRESH_CLAIM_BASE_RELEASE_FAILED.head()
                            + "release failed for task {} whose base refresh could not reach origin",
                    ref.id(),
                    unreleased);
        }
    }
}
