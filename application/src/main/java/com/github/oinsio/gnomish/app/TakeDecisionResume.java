package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.HumanReply;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.DecisionAck;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.status.ReportPlane;
import java.util.List;

/**
 * Resumes an {@code ESCALATION}-kind park ({@code branch.outcome()} is {@code Escalated} with
 * {@code lastEscalation} an {@link EscalationReport.AttemptsExhausted} or {@link
 * EscalationReport.DecisionNeeded} — the two escalation kinds design D3 maps to {@link
 * ParkReason#ESCALATION}): collects human replies posted since the last ack (FR12) and either
 * re-parks a {@code DecisionNeeded} restating the question when no reply is pending yet (FR13,
 * design D12), or commits the freshest pending reply to the branch and acknowledges it — in that
 * order (FR12 of harden-task-branch-contract) — before running the engine.
 *
 * <p>{@code AttemptsExhausted} never re-parks here: the human returning the task to work is itself
 * the confirmation (design D12), matching {@code EscalationResumeDialog#handleResumable}'s
 * blank-answer retry — a pending reply is still passed through when present, since it is meaningful
 * context even though not required.
 *
 * <p>Not for {@code CannotVerify}/{@code CannotExecute}/{@code PipelineMismatch}: those are {@code
 * INFRA}-kind parks resumed via {@link ResumeMechanics#resumeWithoutDecision} instead; a caller
 * error if routed here (checked eagerly). {@link TakeDispositionResume} is the caller that decides.
 *
 * <p>One class serves both execution modes: the dialog is a tracker conversation and identical in
 * either, and the engine run it ends in is reached through {@link ResumeMechanics} (design D8 of
 * add-serve-sandbox-lifecycle).
 *
 * <p>Implements FR12, FR13 of add-tracker-port; FR1 of add-serve-sandbox-lifecycle.
 *
 * @param <B> the loaded-branch bundle {@code mechanics} produces
 * @param mechanics supplies the branch-side decision append and the resumed run; never null
 */
public record TakeDecisionResume<B extends ResumedBranch>(ResumeMechanics<B> mechanics) {

    /**
     * Dispatches on {@code branch.lastEscalation()}'s runtime kind (see class javadoc for the
     * precondition: this method must only be called for an {@code ESCALATION}-kind park).
     *
     * <p>Implements FR12, FR13 of add-tracker-port.
     *
     * @param order the take order being resumed: the clone (never mutated), the interactive mode,
     *     and the tracker used for decision collection, ack and park
     * @param branch the loaded branch; {@code lastEscalation()} must be {@link
     *     EscalationReport.AttemptsExhausted} or {@link EscalationReport.DecisionNeeded}
     * @param finalState the escalated state the park was produced from
     * @return {@link TakeResult.AwaitingHuman} for a restated re-park, or the mapped result of the
     *     resumed engine run
     * @throws IllegalStateException if {@code branch.lastEscalation()} is not an {@code
     *     ESCALATION}-kind report
     */
    public TakeResult resume(TakeOrder order, B branch, TaskState finalState) {
        List<HumanReply> replies = order.tracker().collectDecisions(order.ref());
        HumanReply latest = replies.isEmpty() ? null : replies.getLast();

        return switch (branch.lastEscalation()) {
            case EscalationReport.DecisionNeeded decisionNeeded
            when latest == null -> reparkRestatingQuestion(decisionNeeded, finalState, order.tracker(), order.ref());
            case EscalationReport.DecisionNeeded _ -> ackAndResume(order, branch, finalState, latest);
            case EscalationReport.AttemptsExhausted _
            when latest == null -> mechanics.resumeDecided(order, branch, branch.context(), finalState.resetAttempts());
            case EscalationReport.AttemptsExhausted _ -> ackAndResume(order, branch, finalState, latest);
            case null, default ->
                throw new IllegalStateException(
                        "TakeDecisionResume.resume called for a non-ESCALATION-kind escalation: "
                                + branch.lastEscalation());
        };
    }

    private TakeResult reparkRestatingQuestion(
            EscalationReport.DecisionNeeded decisionNeeded, TaskState finalState, Tracker tracker, TaskRef ref) {
        String report = EscalationResumeDialog.renderEscalation(decisionNeeded, ReportPlane.COMMENT);
        tracker.park(ref, ParkReason.ESCALATION, report);
        return new TakeResult.AwaitingHuman(finalState, ParkReason.ESCALATION, report);
    }

    private TakeResult ackAndResume(TakeOrder order, B branch, TaskState finalState, HumanReply latest) {
        // FR12 of harden-task-branch-contract: the decision is durable on the branch BEFORE its
        // acknowledge posts. Acknowledging first would, on a kill between the two, consume the reply
        // — the next collection starts after the ack — while the branch still carries no answer.
        var resetState = finalState.resetAttempts();
        TaskContext decided = DecisionAck.appendThenAcknowledge(
                order.tracker(),
                order.ref(),
                latest.body(),
                () -> mechanics.appendDecision(order.run().cloneDir(), branch, finalState, resetState, latest.body()));
        return mechanics.resumeDecided(order, branch, decided, resetState);
    }
}
