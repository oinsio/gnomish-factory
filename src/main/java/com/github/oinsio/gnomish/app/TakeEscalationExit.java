package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.TakeOutcomeMapper;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;

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
     * <p>Implements FR13, D12, UX3 of add-tracker-port.
     *
     * @param escalated the fresh engine escalation to exit the run with; never null
     * @param tracker the tracker port the park call is made through; never null
     * @param ref the task's tracker identity; never null
     * @return the {@link TakeResult.AwaitingHuman} the park call was made with; never null
     */
    static TakeResult exit(TaskOutcome.Escalated escalated, Tracker tracker, TaskRef ref) {
        var mapped = (TakeResult.AwaitingHuman) TakeOutcomeMapper.map(escalated);
        ParkReason reason = mapped.reason();

        String rendered = EscalationResumeDialog.renderEscalation(escalated.report());
        String returnPath = reason == ParkReason.ESCALATION ? ESCALATION_RETURN_PATH : INFRA_RETURN_PATH;
        String report = rendered + "\n\n" + returnPath;

        tracker.park(ref, reason, report);
        return new TakeResult.AwaitingHuman(escalated.finalState(), reason, report);
    }
}
