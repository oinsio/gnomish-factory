package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import com.github.oinsio.gnomish.app.terminal.EffectObservation;
import com.github.oinsio.gnomish.app.terminal.TerminalEffect;
import com.github.oinsio.gnomish.app.terminal.TerminalEffectDrive;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The park as one intent→effect→receipt flow (FR10, design D5 of harden-task-branch-contract),
 * shared by the two exits that call {@link Tracker#park(TaskRef, ParkReason, String)} —
 * {@code TakeEscalationExit} ({@code Escalated}) and {@code TakePauseExit} ({@code Paused},
 * {@link ParkReason#CHECKPOINT}) — and by the deferred park a resume reconciles.
 *
 * <p>The steps this flow supplies to {@link TerminalEffectDrive}:
 *
 * <ul>
 *   <li><em>intent</em> — the outcome commit carrying the pending marker, delivered to origin by
 *       the fence, so the park a human is about to be pointed at is durable before the tracker
 *       announces it (FR4, FR5 of fix-lifecycle-push);
 *   <li><em>probe</em> — on a recovered park only: a tracker already reporting the task as awaiting
 *       a human carries the park, so the write is not repeated;
 *   <li><em>effect</em> — the git-unfenced {@code tracker.park}, preceded by {@link
 *       ClaimGuard#stillOurs} (FR7, design D6 of add-claim-heartbeat) so a reaped or taken-over
 *       claim is never clobbered, and bounded by {@link TerminalWriteRetry};
 *   <li><em>receipt</em> — clearing the branch's pending marker, so a later resume reads the park
 *       as settled rather than orphaned.
 * </ul>
 *
 * <p>An unconfirmed park records no receipt: the marker stays set, an ERROR names the unreconciled
 * park, and the next pickup re-drives it — probing the tracker first.
 *
 * <p>Implements FR13, D12 of add-tracker-port; FR7, FR10, D10, NFR-R3 of add-claim-heartbeat; FR10
 * of harden-task-branch-contract.
 */
public final class GuardedPark implements TerminalEffect {

    private final Tracker tracker;
    private final TaskRef ref;
    private final InstanceId instanceId;
    private final ParkReason reason;
    private final Function<String, String> report;
    private final TerminalWriteRetry retry;
    private final ParkTransition transition;
    private final Logger log;
    private final String kind;

    private ParkDeliveryVerdict verdict;
    private @Nullable String reportText;

    private GuardedPark(
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId,
            ParkReason reason,
            Function<String, String> report,
            TerminalWriteRetry retry,
            ParkTransition transition,
            Logger log,
            String kind) {
        this.tracker = tracker;
        this.ref = ref;
        this.instanceId = instanceId;
        this.reason = reason;
        this.report = report;
        this.retry = retry;
        this.transition = transition;
        this.log = log;
        this.kind = kind;
        this.verdict = transition instanceof ParkTransition.Recovered(var recorded, var ignoredReceipt)
                ? recorded
                : new ParkDeliveryVerdict.Delivered();
    }

    /**
     * Drives one park to its end and returns the report text it was written with — the same text the
     * caller's {@link TakeResult.AwaitingHuman} carries, whether the write landed or not.
     *
     * @param tracker the tracker port the park call is made through; never null
     * @param ref the task's tracker identity; never null
     * @param instanceId this factory instance's identity, for the pre-write claim check; never null
     * @param reason the park reason recorded on the tracker; never null
     * @param report builds the operator-facing report from the delivery fence's note, which is known
     *     only once the intent has been recorded; never null
     * @param retry the bounded terminal-write retry the park is made through; never null
     * @param transition the park's branch-side steps — fresh or recovered; never null
     * @param log the caller's logger, so log lines are attributed to the calling class; never null
     * @param kind a short label distinguishing the two callers' log lines (e.g. {@code "park"},
     *     {@code "checkpoint park"}); never null
     * @return the report text the park was written with; never null
     */
    public static String attempt(
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId,
            ParkReason reason,
            Function<String, String> report,
            TerminalWriteRetry retry,
            ParkTransition transition,
            Logger log,
            String kind) {
        var park = new GuardedPark(tracker, ref, instanceId, reason, report, retry, transition, log, kind);
        if (transition instanceof ParkTransition.Fresh) {
            TerminalEffectDrive.deliverFresh(park);
        } else {
            TerminalEffectDrive.redeliver(park);
        }
        return park.reportText();
    }

    @Override
    public void recordIntent() {
        if (transition instanceof ParkTransition.Fresh(var intent, var ignoredReceipt)) {
            verdict = intent.record();
        }
    }

    /**
     * A tracker already reporting the task as awaiting a human carries this park: the write landed
     * and only its receipt was lost. Anything else — including a tracker that cannot be asked —
     * re-drives, which the find-then-upsert write makes safe (FR11).
     */
    @Override
    public EffectObservation observeAtTarget() {
        try {
            return tracker.fetchTask(ref).state() instanceof TrackerTaskState.AwaitingHuman
                    ? EffectObservation.LANDED
                    : EffectObservation.ABSENT;
        } catch (RuntimeException e) {
            log.warn("could not verify whether the {} of {} already landed: {}", kind, ref.id(), e.toString());
            return EffectObservation.UNDETERMINED;
        }
    }

    @Override
    public boolean deliver() {
        if (!ClaimGuard.stillOurs(tracker, ref, instanceId)) {
            log.warn("skipping {} of {}: claim is no longer held by this instance", kind, ref.id());
            return false;
        }
        String text = reportText();
        if (retry.confirm(() -> tracker.park(ref, reason, text)) == TerminalWriteRetry.Result.CONFIRMED) {
            return true;
        }
        log.error(
                "{} of {} could not be written before the retry bound elapsed; the branch keeps the "
                        + "outcome as tracker-write pending and a later resume will reconcile the deferred "
                        + "park",
                kind,
                ref.id());
        return false;
    }

    @Override
    public void recordReceipt() {
        transition.receipt().run();
    }

    /**
     * The report the human reads, built once the delivery fence's verdict is known — an origin that
     * does not yet carry the recorded park adds one line saying so (FR5, UX2 of fix-lifecycle-push).
     */
    private String reportText() {
        if (reportText == null) {
            reportText = report.apply(verdict.reportNote());
        }
        return reportText;
    }
}
