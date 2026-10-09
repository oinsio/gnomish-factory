package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
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
 * the confirmation (design D12), matching {@link EscalationResume#decide}'s
 * decision-less retry — a pending reply is still passed through when present, since it is meaningful
 * context even though not required. Without a reply the reset the return implies is made durable
 * first, through the resumed write (FR7 of make-checkpoint-gate-durable).
 *
 * <p>Not for {@code CannotVerify}/{@code CannotExecute}/{@code PipelineMismatch}: those are {@code
 * INFRA}-kind parks resumed through {@link #resumeReturned} instead; a caller error if routed to
 * {@link #resume} (checked eagerly). {@link TakeLoadedBranchRoutes} is the caller that decides.
 *
 * <p>This class is where a {@code take} return turns into the attempt reset it implies, so every
 * reset it computes reaches {@link ResumeMechanics#appendDecision} or {@link
 * ResumeMechanics#resumeFrom} before the engine runs — never memory alone (FR8, design D4, D7 of
 * make-checkpoint-gate-durable).
 *
 * <p>One class serves both execution modes: the dialog is a tracker conversation and identical in
 * either, and the engine run it ends in is reached through {@link ResumeMechanics} (design D8 of
 * add-serve-sandbox-lifecycle).
 *
 * <p>Implements FR12, FR13 of add-tracker-port; FR1 of add-serve-sandbox-lifecycle; FR7, FR8 of
 * make-checkpoint-gate-durable.
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
     * @param order the take order being resumed: the clone (never mutated) and the tracker used
     *     for decision collection, ack and park
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
            when latest == null -> resumeOnReturnAlone(order, branch, finalState.resetAttempts());
            case EscalationReport.AttemptsExhausted _ -> ackAndResume(order, branch, finalState, latest);
            case null, default ->
                throw new IllegalStateException(
                        "TakeDecisionResume.resume called for a non-ESCALATION-kind escalation: "
                                + branch.lastEscalation());
        };
    }

    /**
     * Continues a recorded non-decision outcome on a return without a reply — an {@code INFRA}-kind
     * escalation returned after the fix, an {@code Aborted} visit retried, or a legacy {@code
     * Paused} recorded past its stage: the resumed write lands first, consuming the outcome in one
     * commit with the state the continuation runs from, then the engine runs once from that state
     * (FR7, design D4 of make-checkpoint-gate-durable). An escalation resets the attempts, the same
     * reset {@link EscalationResume#decide} applies to a decision-less {@code run --resume} — and
     * the one that keeps a {@code CannotVerify} stop on the record from being re-raised; any other
     * outcome burned no budget a return should restore, so its state is kept as recorded.
     *
     * <p>Implements FR7, FR8 of make-checkpoint-gate-durable.
     *
     * @param branch the loaded branch; its {@code outcome()} is recorded and is not {@code
     *     Completed}
     * @param finalState the state the outcome was recorded with
     * @return the mapped result of the resumed engine run
     * @throws com.github.oinsio.gnomish.app.port.ResumedWriteRefusedException when the tip records
     *     no outcome; nothing was written and nothing ran
     */
    public TakeResult resumeReturned(TakeOrder order, B branch, TaskState finalState) {
        TaskState reset =
                branch.outcome() instanceof RecordedOutcome.Escalated ? finalState.resetAttempts() : finalState;
        mechanics.resumeFrom(order, branch, reset);
        return mechanics.resumeWithoutDecision(order, branch, reset);
    }

    /**
     * The bare return of an exhausted stage: the reset lands through the resumed write before the
     * engine runs, so a kill after it cannot grant the budget twice (FR7 of
     * make-checkpoint-gate-durable).
     */
    private TakeResult resumeOnReturnAlone(TakeOrder order, B branch, TaskState reset) {
        mechanics.resumeFrom(order, branch, reset);
        return mechanics.resumeDecided(order, branch, branch.context(), reset);
    }

    private TakeResult reparkRestatingQuestion(
            EscalationReport.DecisionNeeded decisionNeeded, TaskState finalState, Tracker tracker, TaskRef ref) {
        String report = TerminalOutcomeRender.renderEscalation(decisionNeeded, ReportPlane.COMMENT);
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
