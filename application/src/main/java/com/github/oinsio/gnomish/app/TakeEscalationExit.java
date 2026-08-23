package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.GuardedPark;
import com.github.oinsio.gnomish.app.take.TakeOutcomeMapper;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TerminalWriteRetry;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes the gap {@link TakeOutcomeMapper} deliberately leaves open for a fresh {@code
 * Escalated} outcome (task 5.8, design D12): {@link TakeOutcomeMapper#map} decides only the
 * {@link ParkReason} and produces placeholder report text, never calling the tracker — this
 * class builds the real, operator-facing report and performs the actual {@link
 * Tracker#park(TaskRef, ParkReason, String)} call, so a take run ends identically with or
 * without a TTY: park with the report, then exit (FR13, UX3). There is no in-run decision
 * wait — the return path is stated in the report text itself, not a console prompt.
 *
 * <p>The reason split is reused verbatim from {@link TakeOutcomeMapper#map}, not
 * reimplemented: {@code AttemptsExhausted}/{@code DecisionNeeded} need a human decision
 * ({@link ParkReason#ESCALATION}); {@code CannotVerify}/{@code CannotExecute}/{@code
 * PipelineMismatch} need an environment or pipeline fix followed by a bare retry ({@link
 * ParkReason#INFRA}) — no reply is expected or consumed on that path (design D3's
 * one-bypass-attempt protocol). The two reasons get distinct return-path sentences (UX3):
 * {@code ESCALATION} asks the operator to reply and move the task back to ready; {@code
 * INFRA} asks them to fix the issue and move the task back to ready.
 *
 * <p>Scope note: only the {@code Escalated} case is closed here. {@code Completed} → {@code
 * finish} is closed by {@link TakeFinishReport} (task 5.11); {@code Paused} → {@code
 * park(CHECKPOINT)} is closed by {@link TakePauseExit}.
 *
 * <p>Implements FR13, D12, UX3 of add-tracker-port.
 */
final class TakeEscalationExit {

    private static final Logger log = LoggerFactory.getLogger(TakeEscalationExit.class);

    private static final String ESCALATION_RETURN_PATH =
            "Reply in the tracker and move the task back to ready to continue.";
    private static final String INFRA_RETURN_PATH =
            "Fix the environment or pipeline issue, then move the task back to ready to retry.";

    private TakeEscalationExit() {}

    /**
     * Parks {@code escalated} on the tracker with a report combining the rendered escalation
     * description and a reason-appropriate return-path sentence, then returns the matching
     * {@link TakeResult.AwaitingHuman} (FR13, D12, UX3).
     *
     * <p>The {@code tracker.park} write is git-unfenced, so it is guarded by {@link
     * GuardedPark#attempt}, which precedes it with the claim-still-ours check (FR7, design D6 of
     * add-claim-heartbeat): when the claim was reaped or taken over mid-run, the park is skipped
     * with a WARN rather than overwriting the new holder's tracker state — the run still returns
     * the mapped {@link TakeResult.AwaitingHuman} (the branch carries the outcome; the residual
     * TOCTOU costs at most a stray label).
     *
     * <p>Implements FR13, D12, UX3 of add-tracker-port; FR7 of add-claim-heartbeat.
     *
     * @param escalated the fresh engine escalation to exit the run with; never null
     * @param tracker the tracker port the park call is made through; never null
     * @param ref the task's tracker identity; never null
     * @param instanceId this factory instance's identity, for the pre-write claim check; never null
     * @return the {@link TakeResult.AwaitingHuman} the park call was made with; never null
     */
    static TakeResult exit(TaskOutcome.Escalated escalated, Tracker tracker, TaskRef ref, InstanceId instanceId) {
        return exit(escalated, tracker, ref, instanceId, TerminalWriteRetry.system(), () -> {}, "");
    }

    /**
     * As {@link #exit(TaskOutcome.Escalated, Tracker, TaskRef, InstanceId)}, but wraps the
     * git-unfenced {@code tracker.park} write in {@code retry} via {@link GuardedPark#attempt} — a
     * tracker outage at the park line is retried with backoff for the bounded hold-the-slot period
     * (FR10, D10, NFR-R3 of add-claim-heartbeat) — running {@code onConfirmed} once (and only once)
     * the park has landed, clearing the branch's durable "tracker-write pending" marker so a later
     * resume reads the park as settled. On give-up the marker is left set and an ERROR names the
     * unreconciled park; the run still returns {@link TakeResult.AwaitingHuman} (the branch carries
     * the park), and reconcile-on-resume completes the deferred park later. When the pre-write
     * claim-still-ours check shows the claim moved, neither the park nor {@code onConfirmed} runs
     * (the marker stays for the successor's reconcile, never clobbering its state).
     *
     * <p>Implements FR13, D12, UX3 of add-tracker-port; FR7, FR10, D10, NFR-R3 of add-claim-heartbeat.
     *
     * <p>The report gains one extra line when the pre-park delivery fence could not bring origin up
     * to the recorded park (FR5, UX2 of fix-lifecycle-push): the park still lands, and the human
     * reading it learns that the remote does not yet carry the branch they are being asked to look at.
     *
     * @param retry the bounded terminal-write retry the park is made through; never null
     * @param onConfirmed cleared-marker action run once the park confirms landed; never null
     * @param replicationNote the delivery fence's one-line note, or empty when origin carries the
     *     park (or there is no origin at all); never null
     */
    static TakeResult exit(
            TaskOutcome.Escalated escalated,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId,
            TerminalWriteRetry retry,
            Runnable onConfirmed,
            String replicationNote) {
        var mapped = (TakeResult.AwaitingHuman) TakeOutcomeMapper.map(escalated);
        ParkReason reason = mapped.reason();

        String rendered = EscalationResumeDialog.renderEscalation(escalated.report());
        String returnPath = reason == ParkReason.ESCALATION ? ESCALATION_RETURN_PATH : INFRA_RETURN_PATH;
        String report = rendered + "\n\n" + returnPath + (replicationNote.isEmpty() ? "" : "\n" + replicationNote);

        GuardedPark.attempt(tracker, ref, instanceId, reason, report, retry, onConfirmed, log, "park");
        return new TakeResult.AwaitingHuman(escalated.finalState(), reason, report);
    }
}
