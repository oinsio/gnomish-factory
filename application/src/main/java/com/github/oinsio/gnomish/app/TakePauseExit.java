package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.ClaimGuard;
import com.github.oinsio.gnomish.app.take.TakeOutcomeMapper;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TerminalWriteRetry;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.status.LiveActivity;
import com.github.oinsio.gnomish.status.StatusReport;
import com.github.oinsio.gnomish.status.StatusTextRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes the gap {@link TakeOutcomeMapper} deliberately leaves open for a fresh {@code Paused}
 * outcome: {@link TakeOutcomeMapper#map} decides only the {@link ParkReason#CHECKPOINT} and
 * produces placeholder report text, never calling the tracker — this class renders the real,
 * operator-facing checkpoint report and performs the actual {@link Tracker#park(TaskRef,
 * ParkReason, String)} call, so a {@code take} run ends identically with or without a TTY (design
 * D12), mirroring how {@link TakeEscalationExit} closes the {@code Escalated} gap (task 5.8) and
 * {@link TakeFinishReport} the {@code Completed} gap (task 5.11). Without this call a manual
 * checkpoint exited with code 11 but the task stayed {@code Working} in the tracker, violating the
 * tracker-port "Outcome-to-transition mapping" scenario ({@code Paused} SHALL appear as {@code
 * AwaitingHuman(checkpoint)}).
 *
 * <p>The report is {@link StatusTextRenderer#renderFull(StatusReport)}'s full text block plus a
 * line naming the stage that passed and triggered the checkpoint, and a return-path sentence — a
 * checkpoint resumes on the human returning the task to ready (no reply is expected or consumed,
 * unlike an {@code ESCALATION} park). {@code attemptLimit} is passed as {@code null} to {@link
 * StatusReport#build}, the same choice {@link TakeFinishReport} makes for a terminal outcome where
 * there is no current stage to resolve a limit for.
 *
 * <p>Implements FR13, FR18, D12 of add-tracker-port.
 */
final class TakePauseExit {

    private static final Logger log = LoggerFactory.getLogger(TakePauseExit.class);

    private static final String CHECKPOINT_RETURN_PATH =
            "Review the work, then move the task back to ready to continue.";

    private TakePauseExit() {}

    /**
     * Parks {@code paused} on the tracker as {@code AwaitingHuman(CHECKPOINT)} with a rendered
     * checkpoint report, then returns the matching {@link TakeResult.AwaitingHuman} (FR13, FR18,
     * D12).
     *
     * <p>The {@code tracker.park} write is git-unfenced, so it is preceded by {@link
     * ClaimGuard#stillOurs} (FR7, design D6 of add-claim-heartbeat): when the claim was reaped or
     * taken over mid-run, the checkpoint park is skipped with a WARN rather than overwriting the new
     * holder's tracker state — the run still returns the mapped {@link TakeResult.AwaitingHuman}.
     *
     * <p>Implements FR13, FR18, D12 of add-tracker-port; FR7 of add-claim-heartbeat.
     *
     * @param paused the fresh engine checkpoint pause to exit the run with; never null
     * @param context the task's identity and decisions, reflecting all decisions up to this run;
     *     never null
     * @param branchName the task branch's short name, appended as a report line; never null
     * @param tracker the tracker port the park call is made through; never null
     * @param ref the task's tracker identity; never null
     * @param instanceId this factory instance's identity, for the pre-write claim check; never null
     * @return the {@link TakeResult.AwaitingHuman} the park call was made with; never null
     */
    static TakeResult finish(
            TaskOutcome.Paused paused,
            TaskContext context,
            String branchName,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        return finish(paused, context, branchName, tracker, ref, instanceId, TerminalWriteRetry.system(), () -> {});
    }

    /**
     * As {@link #finish(TaskOutcome.Paused, TaskContext, String, Tracker, TaskRef, InstanceId)}, but
     * wraps the git-unfenced checkpoint {@code tracker.park} write in {@code retry} — a tracker
     * outage is retried with backoff for the bounded hold-the-slot period (FR10, D10, NFR-R3 of
     * add-claim-heartbeat) — and runs {@code onConfirmed} once the park lands, clearing the branch's
     * "tracker-write pending" marker. On give-up the marker is left set and an ERROR names the
     * unreconciled checkpoint park; reconcile-on-resume completes it later. When the pre-write {@link
     * ClaimGuard} shows the claim moved, neither the park nor {@code onConfirmed} runs.
     *
     * <p>Implements FR13, FR18, D12 of add-tracker-port; FR7, FR10, D10, NFR-R3 of add-claim-heartbeat.
     *
     * @param retry the bounded terminal-write retry the park is made through; never null
     * @param onConfirmed cleared-marker action run once the park confirms landed; never null
     */
    static TakeResult finish(
            TaskOutcome.Paused paused,
            TaskContext context,
            String branchName,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId,
            TerminalWriteRetry retry,
            Runnable onConfirmed) {
        var report = StatusReport.build(context, paused.finalState(), null, LiveActivity.idle());
        String rendered = new StatusTextRenderer().renderFull(report);
        String checkpoint = "Stage '" + paused.passedStage() + "' passed. Manual checkpoint reached.";
        String reportText =
                rendered + "\n" + "Branch: " + branchName + "\n\n" + checkpoint + "\n" + CHECKPOINT_RETURN_PATH;

        if (ClaimGuard.stillOurs(tracker, ref, instanceId)) {
            if (retry.confirm(() -> tracker.park(ref, ParkReason.CHECKPOINT, reportText))
                    == TerminalWriteRetry.Result.CONFIRMED) {
                onConfirmed.run();
            } else {
                log.error(
                        "checkpoint park of {} could not be written before the retry bound elapsed; the branch keeps "
                                + "the outcome as tracker-write pending and a later resume will reconcile the deferred park",
                        ref.id());
            }
        } else {
            log.warn("skipping checkpoint park of {}: claim is no longer held by this instance", ref.id());
        }
        return new TakeResult.AwaitingHuman(paused.finalState(), ParkReason.CHECKPOINT, reportText);
    }
}
