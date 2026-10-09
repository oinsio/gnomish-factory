package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.console.DialogConsole;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.status.ReportPlane;
import java.time.InstantSource;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;

/**
 * The one place a {@code run} resume decision becomes a {@link RunnerOutcomeLoop.Resumption}
 * (design D2, D7 of make-run-headless): the operator's {@code --decision}, or its absence, meets
 * the recorded {@code Escalated} outcome here and nowhere else. A decision is appended to the task
 * context (author {@code operator}, the current stage, now) and the attempts are reset; no decision
 * resets the attempts alone — an infrastructure fix needs no message (FR3, FR4). A {@code
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
 * <p>Implements FR3, FR4 of make-run-headless.
 */
public final class EscalationResume {

    private final DialogConsole console;
    private final InstantSource clock;
    private final TerminalOutcomeRender.ReturnPath returnPath;

    /**
     * @param console the console owner the restated question is printed through; never read
     * @param clock the time source stamped on every appended {@link Decision}; never null
     * @param returnPath where the task resumes from, named by the restatement; never null — a
     *     resume always has a branch
     */
    EscalationResume(DialogConsole console, InstantSource clock, TerminalOutcomeRender.ReturnPath returnPath) {
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
     * Appends a new {@link Decision} — {@code author = "operator"}, {@code body = decision}, {@code
     * time} from {@link #clock} — scoped to the current stage name when {@code position} resolves to
     * one (it always does here: {@code PipelineMismatch} is refused above and never leaves {@code
     * AtStage}).
     */
    private TaskContext appendDecision(TaskContext context, Position position, String decision) {
        String stage = position instanceof Position.AtStage(String name) ? name : null;
        var decisions = new ArrayList<>(context.decisions());
        decisions.add(new Decision(decision, stage, "operator", clock.instant()));
        return new TaskContext(context.taskId(), context.title(), context.body(), decisions);
    }
}
