package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.HumanReply;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.nio.file.Path;
import java.util.List;

/**
 * Resumes an {@code ESCALATION}-kind park ({@code branch.outcome()} is {@code Escalated} with
 * {@code lastEscalation} an {@link EscalationReport.AttemptsExhausted} or {@link
 * EscalationReport.DecisionNeeded} — the two escalation kinds design D3 maps to {@link
 * ParkReason#ESCALATION}): collects human replies posted since the last ack (FR12) and either
 * re-parks a {@code DecisionNeeded} restating the question when no reply is pending yet (FR13,
 * design D12), or acks the freshest pending reply before running the engine (FR12,
 * ack-before-acting).
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
 * @param mechanics supplies {@link ResumeMechanics#resumeWithDecision}; never null
 */
public record TakeDecisionResume<B extends ResumedBranch>(ResumeMechanics<B> mechanics) {

    /**
     * Dispatches on {@code branch.lastEscalation()}'s runtime kind (see class javadoc for the
     * precondition: this method must only be called for an {@code ESCALATION}-kind park).
     *
     * <p>Implements FR12, FR13 of add-tracker-port.
     *
     * @param cloneDir the project clone; never mutated
     * @param branch the loaded branch; {@code lastEscalation()} must be {@link
     *     EscalationReport.AttemptsExhausted} or {@link EscalationReport.DecisionNeeded}
     * @param finalState the escalated state the park was produced from
     * @param interactiveMode which role(s) use the interactive adapter
     * @param tracker the tracker port: decision collection, ack, park
     * @param ref the task's tracker identity
     * @param instanceId this factory instance's identity
     * @return {@link TakeResult.AwaitingHuman} for a restated re-park, or the mapped result of the
     *     resumed engine run
     * @throws IllegalStateException if {@code branch.lastEscalation()} is not an {@code
     *     ESCALATION}-kind report
     */
    public TakeResult resume(
            Path cloneDir,
            B branch,
            TaskState finalState,
            RunArguments.InteractiveMode interactiveMode,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        List<HumanReply> replies = tracker.collectDecisions(ref);
        HumanReply latest = replies.isEmpty() ? null : replies.getLast();

        return switch (branch.lastEscalation()) {
            case EscalationReport.DecisionNeeded decisionNeeded
            when latest == null -> reparkRestatingQuestion(decisionNeeded, finalState, tracker, ref);
            case EscalationReport.DecisionNeeded _ ->
                ackAndResume(cloneDir, branch, finalState, interactiveMode, tracker, ref, instanceId, latest);
            case EscalationReport.AttemptsExhausted _
            when latest == null ->
                mechanics.resumeWithDecision(
                        cloneDir, branch, finalState, null, interactiveMode, tracker, ref, instanceId);
            case EscalationReport.AttemptsExhausted _ ->
                ackAndResume(cloneDir, branch, finalState, interactiveMode, tracker, ref, instanceId, latest);
            case null, default ->
                throw new IllegalStateException(
                        "TakeDecisionResume.resume called for a non-ESCALATION-kind escalation: "
                                + branch.lastEscalation());
        };
    }

    private TakeResult reparkRestatingQuestion(
            EscalationReport.DecisionNeeded decisionNeeded, TaskState finalState, Tracker tracker, TaskRef ref) {
        String report = EscalationResumeDialog.renderEscalation(decisionNeeded);
        tracker.park(ref, ParkReason.ESCALATION, report);
        return new TakeResult.AwaitingHuman(finalState, ParkReason.ESCALATION, report);
    }

    private TakeResult ackAndResume(
            Path cloneDir,
            B branch,
            TaskState finalState,
            RunArguments.InteractiveMode interactiveMode,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId,
            HumanReply latest) {
        tracker.acknowledgeDecision(ref, latest.body());
        return mechanics.resumeWithDecision(
                cloneDir, branch, finalState, latest.body(), interactiveMode, tracker, ref, instanceId);
    }
}
