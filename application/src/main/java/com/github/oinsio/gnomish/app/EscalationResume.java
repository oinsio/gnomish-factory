package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.console.DialogConsole;
import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.status.ReportPlane;
import java.time.Clock;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;

/**
 * The one place a {@code run} resume decision becomes a {@link RunnerOutcomeLoop.Resumption}
 * (design D2, D7 of make-run-headless): the operator's {@code --decision}, or its absence, meets
 * the recorded {@code Escalated} outcome here and nowhere else. A decision is appended to the task
 * context (author {@code operator}, the current stage, now) and the attempts are reset; no decision
 * resets the attempts alone — an infrastructure fix needs no message (FR3, FR4). Either way {@link
 * #land} writes the result before the engine runs: the decision commit, or the resumed commit —
 * never a reset held in memory (FR7 of make-checkpoint-gate-durable). A {@code
 * DecisionNeeded} report resumed without a decision is refused: the question is restated on the
 * console with its return-path line and {@link DecisionRequiredException} leaves the process at
 * exit 10, no attempt burned, nothing written (FR4). A {@code PipelineMismatch} cannot be resumed
 * at all and is an {@link InternalErrorException}, as the in-process loop treats it.
 *
 * <p>There is no console to ask: the decision is a parameter, which is what keeps this the single
 * owner — the console is held for the restatement only. The consumers are the two resume
 * continuations, {@link GitResumeContinuation#resumeEscalated} and {@link
 * ContainerResumeOutcomes#resumeEscalated}; the prompt-driven predecessor they used to share was
 * deleted with their switch. {@code TakeDecisionResume} keeps its own reset: {@code take}'s decision
 * arrives as a tracker reply under an acknowledge protocol, not as a flag.
 *
 * <p>Implements FR3, FR4 of make-run-headless; FR7 of make-checkpoint-gate-durable.
 */
public final class EscalationResume {

    private final DialogConsole console;
    private final Clock clock;
    private final TerminalOutcomeRender.ReturnPath returnPath;

    /**
     * @param console the console owner the restated question is printed through; never read
     * @param clock the time source stamped on every appended {@link Decision}; never null
     * @param returnPath where the task resumes from, named by the restatement; never null — a
     *     resume always has a branch
     */
    EscalationResume(DialogConsole console, Clock clock, TerminalOutcomeRender.ReturnPath returnPath) {
        this.console = console;
        this.clock = clock;
        this.returnPath = returnPath;
    }

    /**
     * Resolves a recorded escalation into what the engine continues with.
     *
     * <p>Implements FR3, FR4 of make-run-headless.
     *
     * @param context the task context the escalation was produced from; never null
     * @param escalated the recorded escalated outcome; never null
     * @param decision the operator's {@code --decision}, or {@code null} when none was given
     * @return the context — decision appended when one was given — and the attempts-reset state
     * @throws DecisionRequiredException for a {@code DecisionNeeded} report and no decision, after
     *     the question has been restated on the console
     * @throws InternalErrorException for a {@code PipelineMismatch}, which no decision can resume
     */
    RunnerOutcomeLoop.Resumption decide(
            TaskContext context, TaskOutcome.Escalated escalated, @Nullable String decision) {
        EscalationReport report = escalated.report();
        if (report instanceof EscalationReport.PipelineMismatch) {
            throw new InternalErrorException(TerminalOutcomeRender.renderEscalation(report, ReportPlane.CONSOLE));
        }
        if (decision == null && report instanceof EscalationReport.DecisionNeeded) {
            console.print(TerminalOutcomeRender.escalated(report, returnPath) + ConsoleIO.LINE_END);
            throw new DecisionRequiredException(escalated, returnPath);
        }
        var finalState = escalated.finalState();
        var resumedContext = decision == null ? context : appendDecision(context, finalState.position(), decision);
        return new RunnerOutcomeLoop.Resumption(resumedContext, finalState.resetAttempts());
    }

    /**
     * Lands what {@link #decide} resolved as the one lifecycle commit before the engine runs (FR4,
     * FR7, design D4 of make-checkpoint-gate-durable): a decision through {@link
     * TaskRepository#appendDecision} — the decision, the attempts reset and the cleared outcome in
     * one commit — and no decision through {@link TaskRepository#resumeFrom}, the reset and the
     * cleared outcome in one commit, so the budget a resume grants is granted once and never lives
     * in memory alone. The two {@code run} continuations call it after {@link #decide}, the
     * container one after disposing its kept box.
     *
     * <p>Implements FR3 of make-run-headless; FR7, FR8 of make-checkpoint-gate-durable.
     *
     * @param repository the medium's lifecycle writer; never null
     * @param taskId the resumed task, as the branch records it
     * @param resumption what {@link #decide} returned for {@code decision}
     * @param decision the operator's {@code --decision}, or {@code null} when none was given
     */
    static void land(
            TaskRepository repository,
            String taskId,
            RunnerOutcomeLoop.Resumption resumption,
            @Nullable String decision) {
        if (decision != null) {
            repository.appendDecision(taskId, resumption.context().decisions().getLast(), resumption.state());
        } else {
            repository.resumeFrom(taskId, resumption.state());
        }
    }

    /**
     * Appends a new {@link Decision} — {@code author = "operator"}, {@code body = decision}, {@code
     * time} from {@link #clock} — scoped to the current stage name when {@code position} resolves to
     * one (it always does here: {@code PipelineMismatch} is refused above and never leaves {@code
     * AtStage}).
     */
    private TaskContext appendDecision(TaskContext context, Position position, String decision) {
        String stage =
                switch (position) {
                    case Position.AtStage(String name) -> name;
                    // FR1 of make-checkpoint-gate-durable: a gate names the stage that passed.
                    case Position.AwaitingApproval(String gate) -> gate;
                    case Position.PipelineEnd() -> null;
                };
        var decisions = new ArrayList<>(context.decisions());
        decisions.add(new Decision(decision, stage, "operator", clock.instant()));
        return new TaskContext(context.taskId(), context.title(), context.body(), decisions);
    }
}
