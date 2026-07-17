package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.console.ConsoleClosedException;
import com.github.oinsio.gnomish.adapter.console.DialogConsole;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Handles an {@code Escalated} outcome on behalf of {@link RunnerOutcomeLoop} (task 7.5,
 * D8, FR9): routes {@code PipelineMismatch} to {@link InternalErrorException}, applies
 * the Case-1 EOF short-circuit (task 7.8, D2, FR13, NFR-R1), and otherwise runs the
 * resumable-escalation dialog — render, prompt for a decision, reset {@code
 * attemptsUsed}, optionally append a {@link Decision} — to build the {@link
 * RunnerOutcomeLoop.Resumption} to loop back into the engine with. Extracted from
 * {@link RunnerOutcomeLoop} purely to keep both files within the project's file-size
 * guidance (`.claude/rules/process-invariants.md`); the behavior is unchanged.
 *
 * <p>Implements FR9, FR13, NFR-R1, D2, D8, D10 of add-manual-run.
 */
final class EscalationResumeDialog {

    private final DialogConsole console;
    private final Clock clock;

    /**
     * @param console the single input choke point used for the escalation resume
     *     dialog; never null
     * @param clock the time source stamped on every appended {@link Decision}; never
     *     null
     */
    EscalationResumeDialog(DialogConsole console, Clock clock) {
        this.console = console;
        this.clock = clock;
    }

    /**
     * Routes an {@code Escalated} outcome to its internal-error or resumable path
     * (task 7.4/7.5, D8), then — for the resumable path only — applies the Case-1 EOF
     * short-circuit (task 7.8, D2, FR13, NFR-R1): if the console's input is already
     * exhausted, EOF happened earlier, deeper in the stack (inside an interactive
     * adapter mid-stage), and the resulting infrastructure failure is what produced
     * this very {@code Escalated} outcome. Prompting again would re-enter an
     * input-requiring dialog on a console that cannot produce more input (NFR-R1), so
     * this throws {@link InputExhaustedException} instead of running the resume
     * dialog — {@link RunExitCodeMapper} maps that exception to exit code 4. No
     * farewell line is printed here; see {@link InputExhaustedException}'s javadoc
     * for why that is the CLI boundary's job.
     *
     * <p>Implements FR9, FR13, NFR-R1, D2, D8 of add-manual-run.
     *
     * @param context the task context the dispatched outcome was produced from;
     *     never null
     * @param escalated the escalated outcome to resolve; never null
     * @return the context/state pair to resume the engine with
     */
    RunnerOutcomeLoop.@Nullable Resumption handle(TaskContext context, TaskOutcome.Escalated escalated) {
        String rendered = renderEscalation(escalated.report());
        if (escalated.report() instanceof EscalationReport.PipelineMismatch) {
            throw new InternalErrorException(rendered);
        }
        if (console.inputExhausted()) {
            throw new InputExhaustedException();
        }
        return handleResumable(context, escalated, rendered);
    }

    /**
     * Renders every {@link EscalationReport} variant as a distinct, kind-specific English text
     * block by an exhaustive switch — no {@code default} arm — so a new variant fails to compile
     * here until its render is added (FR9): the text doubles as both the resume-dialog prompt
     * and the internal-error message for {@code PipelineMismatch}.
     *
     * <p>Implements FR9, D8 of add-manual-run.
     *
     * @param report the escalation reason to render; never null
     * @return the rendered text block; never null, never blank
     */
    static String renderEscalation(EscalationReport report) {
        return switch (report) {
            case EscalationReport.AttemptsExhausted attemptsExhausted ->
                "Attempt limit (" + attemptsExhausted.limit() + ") reached — every attempt failed quality.";
            case EscalationReport.DecisionNeeded decisionNeeded ->
                "The gnome asked: " + decisionNeeded.question() + "\nOptions: "
                        + String.join(", ", decisionNeeded.options());
            case EscalationReport.CannotVerify cannotVerify ->
                "Could not verify check " + cannotVerify.check().label() + ": " + cannotVerify.reason() + "\n"
                        + cannotVerify.details();
            case EscalationReport.PipelineMismatch pipelineMismatch ->
                "Stage '" + pipelineMismatch.staleStage() + "' is no longer defined in the pipeline.";
            case EscalationReport.CannotExecute cannotExecute ->
                "Executor infrastructure failure: " + cannotExecute.cause();
        };
    }

    /**
     * Resumes a non-{@code PipelineMismatch} escalation (task 7.5, D8, FR9): prints
     * the already-rendered report, prompts for a decision, and builds the reset
     * {@link TaskState} (same position, {@code attemptsUsed} 0, empty attempt
     * history, {@code totals} preserved) and the possibly decision-appended {@link
     * TaskContext} to loop back into the engine with. A blank answer (bare Enter)
     * resumes without appending a {@link Decision} — an infrastructure fix needs no
     * message (FR9, "Empty decision retries after an environment fix"). A {@link
     * ConsoleClosedException} at this very prompt (Case 2, deliberate Ctrl-D) is
     * caught and rethrown as {@link EscalationEofException} so {@link
     * RunExitCodeMapper} can route it to exit 10, distinct from the checkpoint
     * prompt's {@link CheckpointEofException} (exit 11).
     *
     * <p>Implements FR9, D2, D8, D10 of add-manual-run.
     */
    private RunnerOutcomeLoop.Resumption handleResumable(
            TaskContext context, TaskOutcome.Escalated escalated, String rendered) {
        console.print(rendered);
        String answer;
        try {
            answer = console.prompt("Decision (empty to resume without one): ");
        } catch (ConsoleClosedException closed) {
            throw new EscalationEofException(closed);
        }

        var finalState = escalated.finalState();
        var resetState = new TaskState(finalState.position(), 0, List.of(), finalState.totals());
        var resumedContext = answer.isBlank() ? context : appendDecision(context, finalState.position(), answer);

        return new RunnerOutcomeLoop.Resumption(resumedContext, resetState);
    }

    /**
     * Appends a new {@link Decision} — {@code author = "operator"}, {@code body =
     * answer}, {@code time} from {@link #clock} — to {@code context}'s decision
     * list, scoped to the current stage name when {@code position} resolves to one
     * (it always does here, since {@code PipelineMismatch} is handled before this
     * point and never leaves {@code AtStage}).
     */
    private TaskContext appendDecision(TaskContext context, Position position, String answer) {
        String stage = position instanceof Position.AtStage atStage ? atStage.name() : null;
        var decisions = new ArrayList<>(context.decisions());
        decisions.add(new Decision(answer, stage, "operator", clock.instant()));
        return new TaskContext(context.taskId(), context.title(), context.body(), decisions);
    }
}
