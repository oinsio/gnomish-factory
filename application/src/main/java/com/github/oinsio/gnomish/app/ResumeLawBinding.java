package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.BaseRefGit;
import com.github.oinsio.gnomish.app.port.git.ResumeBaseOutcome;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.ResumeBaseReport;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import java.nio.file.Path;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The resume-time law rebind of {@code take --resume} (FR12, design D13 of add-base-ref-resolution):
 * a resumed task never re-resolves its base (FR7) — the pinned ref name is bound again, unchanged —
 * but the law commit it runs under is the CURRENT TIP of that ref, so a human fix to instructions or
 * acceptance criteria on the base reaches a returned task without a re-pin. A clone with {@code
 * origin} configured narrow-fetches the pinned ref exactly as the fresh-claim base refresh does (a
 * SHA pin costs no network there either, since the object is already in the task branch's history);
 * a clone with no {@code origin} at all binds from the ref's LOCAL tip instead of refusing, since a
 * resume with nothing to fetch from is a legitimate shape a fresh claim never has to handle (D14
 * fails a fresh {@code take}/{@code serve} fast without one). A ref that resolves nowhere PARKS the
 * task with a report rather than silently falling back to the pinned SHA; a configured remote that
 * never answers is an infrastructure failure that releases the claim, the same model D9 charges the
 * daemon for on a fresh claim's base-refresh outage.
 *
 * <p>The one place both {@link TakeResumeRunner} and {@link TakeContainerResumeRunner} bind their
 * resumed law from — see the {@code Kept in sync with} markers on each. The manual {@code run
 * --resume} paths, which hold no claim and so have no tracker to park or release a task with, bind
 * the same pinned ref offline through {@link ManualResumeLawBinding}.
 *
 * <p><b>Pinned ref source (task 6.4, FR7).</b> Both callers pass {@link #pinnedRef} the bundle's
 * {@code baseRef} — the durable pin's ref name, e.g. {@code release/1.18} — falling back to {@code
 * baseCommit} (a bare SHA, itself a valid {@link BaseRefGit#resolveForResume} input) only for a
 * legacy branch created before the structured pin existed, whose {@code task.json} carries no
 * {@code baseRef} at all.
 *
 * <p>Implements FR7, FR12, D13 of add-base-ref-resolution.
 */
final class ResumeLawBinding {

    private static final Logger log = LoggerFactory.getLogger(ResumeLawBinding.class);

    private ResumeLawBinding() {}

    /** What the rebind decided: the law to run under, or the terminal result that ends the resume. */
    sealed interface Outcome permits Bound, Parked, Released {}

    /**
     * The pinned ref's current tip was established.
     *
     * @param lawBinding the binding the run freezes its law from — the resolved tip, so a resumed
     *     task never reads working-tree state (D12)
     */
    record Bound(LawBinding lawBinding) implements Outcome {}

    /**
     * The pinned ref resolves nowhere and the task was parked.
     *
     * @param result the terminal result carrying the report
     */
    record Parked(TakeResult result) implements Outcome {}

    /**
     * A configured remote never answered and the claim was released. The result is {@link
     * TakeResult.InfrastructureUnavailable} — the outage is the daemon's condition, never the
     * task's (FR9, design D9/D13 of add-base-ref-resolution), so a serve slot ending here opens
     * the remote outage gate exactly as a fresh claim's does.
     *
     * @param result the terminal result the run ends with
     */
    record Released(TakeResult result) implements Outcome {}

    /**
     * Resolves {@code pinnedRef}'s current tip and decides the outcome.
     *
     * @param baseRefGit the base-ref capability the resolution reads through; never null
     * @param cloneDir the factory clone the resolution runs against; never null
     * @param pinnedRef the task's pinned base ref — see {@link #pinnedRef(String, String)} for how
     *     callers derive it; never null
     * @param finalState the state the resume was about to run from — reported unchanged on a park,
     *     since neither outcome makes engine progress; never null
     * @param ref the task's tracker identity; never null
     * @param tracker the tracker port for the best-effort park/release; never null
     * @return the bound law, the park result, or the release result; never null
     */
    static Outcome bind(
            BaseRefGit baseRefGit,
            Path cloneDir,
            String pinnedRef,
            TaskState finalState,
            TaskRef ref,
            Tracker tracker) {
        return switch (baseRefGit.resolveForResume(cloneDir, pinnedRef)) {
            case ResumeBaseOutcome.Bound(var ignoredRef, String commit) ->
                new Bound(LawBinding.atRevision(cloneDir, commit));
            case ResumeBaseOutcome.Refused(String report) ->
                new Parked(park(finalState, ref, tracker, pinnedRef, report));
            case ResumeBaseOutcome.Unavailable(String reason) -> new Released(release(ref, tracker, pinnedRef, reason));
        };
    }

    /**
     * The ref both {@link #bind} callers resolve from: the durable pin's ref name when the branch
     * carries one, falling back to the recorded base commit (a bare SHA, itself a valid {@link
     * BaseRefGit#resolveForResume} input) for a legacy branch predating the structured pin (FR7 of
     * add-base-ref-resolution). A pinned task never re-resolves either way — this only selects
     * which already-recorded value names the tip to refresh.
     *
     * @param baseRef the branch's durable pin, or {@code null} for a legacy branch
     * @param baseCommit the branch's recorded base commit; never null
     * @return {@code baseRef} if present, else {@code baseCommit}; never null
     */
    static String pinnedRef(@Nullable String baseRef, String baseCommit) {
        return baseRef != null ? baseRef : baseCommit;
    }

    /**
     * {@link #bind} plus the routing both {@link TakeResumeRunner} and {@link
     * TakeContainerResumeRunner} need from it: a park/release result short-circuits, a bound law is
     * handed to {@code continuation} to build and run the engine — so neither caller spells the
     * three-way switch itself.
     */
    static TakeResult resolve(
            BaseRefGit baseRefGit,
            Path cloneDir,
            String pinnedRef,
            TaskState finalState,
            TaskRef ref,
            Tracker tracker,
            Function<LawBinding, TakeResult> continuation) {
        return switch (bind(baseRefGit, cloneDir, pinnedRef, finalState, ref, tracker)) {
            case Parked(TakeResult parked) -> parked;
            case Released(TakeResult released) -> released;
            case Bound(LawBinding lawBinding) -> continuation.apply(lawBinding);
        };
    }

    private static TakeResult park(
            TaskState finalState, TaskRef ref, Tracker tracker, String pinnedRef, String report) {
        String fullReport = ResumeBaseReport.unresolved(ref.id(), pinnedRef, report);
        log.error(
                OperatorEvent.RESUME_PINNED_REF_UNRESOLVED.head()
                        + "parking task {}: its pinned base ref '{}' no longer resolves: {}",
                ref.id(),
                pinnedRef,
                LogText.forLog(report));
        parkBestEffort(tracker, ref, fullReport);
        return new TakeResult.AwaitingHuman(finalState, ParkReason.INFRA, fullReport);
    }

    private static void parkBestEffort(Tracker tracker, TaskRef ref, String report) {
        try {
            tracker.park(ref, ParkReason.INFRA, report);
        } catch (RuntimeException unparked) {
            log.error(
                    OperatorEvent.RESUME_BASE_PARK_FAILED.head()
                            + "park(INFRA) failed for task {} whose pinned base ref no longer resolves; stopping"
                            + " for a human anyway",
                    ref.id(),
                    unparked);
        }
    }

    private static TakeResult release(TaskRef ref, Tracker tracker, String pinnedRef, String reason) {
        log.warn(
                OperatorEvent.RESUME_BASE_REFRESH_UNAVAILABLE.head()
                        + "releasing claim on task {}: origin never answered the resume refresh of its pinned"
                        + " base ref '{}': {}",
                ref.id(),
                pinnedRef,
                LogText.forLog(reason));
        releaseBestEffort(tracker, ref);
        return new TakeResult.InfrastructureUnavailable("Task " + ref.id() + " was returned to Ready: origin did"
                + " not answer the resume refresh of its pinned base ref '" + pinnedRef + "': " + reason);
    }

    private static void releaseBestEffort(Tracker tracker, TaskRef ref) {
        try {
            tracker.release(ref);
        } catch (RuntimeException unreleased) {
            log.error(
                    OperatorEvent.RESUME_BASE_RELEASE_FAILED.head()
                            + "release failed for task {} whose resume base refresh could not reach origin",
                    ref.id(),
                    unreleased);
        }
    }
}
