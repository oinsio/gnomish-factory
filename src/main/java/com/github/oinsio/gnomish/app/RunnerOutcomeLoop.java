package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.console.ConsoleClosedException;
import com.github.oinsio.gnomish.adapter.console.DialogConsole;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.Engine;
import com.github.oinsio.gnomish.domain.engine.EnginePorts;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.status.LiveActivity;
import com.github.oinsio.gnomish.status.StatusReport;
import com.github.oinsio.gnomish.status.StatusTextRenderer;
import java.time.Clock;
import org.jspecify.annotations.Nullable;

/**
 * The runner's outcome loop (design D8): repeatedly calls {@link Engine#run} and dispatches the
 * returned {@link TaskOutcome} by an exhaustive switch — no {@code default} arm — so a new
 * variant fails to compile here until its branch is added.
 *
 * <p>{@code PipelineMismatch} ("unreachable in-process") becomes {@link InternalErrorException};
 * every other {@code Escalated} kind resumes through {@link EscalationResumeDialog} (extracted
 * to keep this file within the file-size guidance). {@code Paused} resumes through {@link
 * #handlePaused}: confirmation only, nothing to reset. {@code Aborted} is terminal: {@link
 * #handleAborted} prints to {@link System#err} then throws {@link AbortedException} so the CLI
 * boundary can tell it apart from {@code Completed}, which renders a final status summary and
 * returns normally (exit 0 is Spring Boot's default for a returning runner).
 *
 * <p>Implements FR9, D8, D10 of add-manual-run.
 */
public final class RunnerOutcomeLoop {

    private final Engine engine;
    private final DialogConsole console;
    private final EscalationResumeDialog escalationDialog;
    private final StatusTextRenderer statusRenderer = new StatusTextRenderer();

    /**
     * @param engine the pure orchestrator this loop drives repeatedly; never null
     * @param console the single input choke point; never null
     * @param clock the time source stamped on every appended {@link Decision}; never null
     */
    public RunnerOutcomeLoop(Engine engine, DialogConsole console, Clock clock) {
        this.engine = engine;
        this.console = console;
        this.escalationDialog = new EscalationResumeDialog(console, clock);
    }

    /**
     * Runs {@code definition}/{@code context} from {@code initialState} through the engine,
     * dispatching each returned {@link TaskOutcome} via {@link #dispatch}. Resumable {@code
     * Escalated}/{@code Paused} outcomes loop back into the engine; every other branch is
     * terminal, so the loop always exits via {@code return} or an exception.
     *
     * <p>Implements FR9, D8 of add-manual-run.
     *
     * @param definition the pipeline the run advances through; never null
     * @param context the task's identity and human decisions; never null
     * @param initialState the state the first {@link Engine#run} call resumes from; never null
     * @param workspace the opaque working copy the run operates on; never null
     * @param ports the collaborators the engine drives; never null
     */
    public void run(
            PipelineDefinition definition,
            TaskContext context,
            TaskState initialState,
            Workspace workspace,
            EnginePorts ports) {
        var currentContext = context;
        var currentState = initialState;
        while (true) {
            var outcome = engine.run(definition, currentContext, currentState, workspace, ports);
            var resumed = dispatch(currentContext, outcome);
            if (resumed == null) {
                return;
            }
            currentContext = resumed.context();
            currentState = resumed.state();
        }
    }

    /**
     * Dispatches one {@link TaskOutcome} by an exhaustive switch over its four sealed variants
     * (design D8). Extracted from {@link #run} so tests can exercise dispatch directly, without
     * constructing a full {@link EnginePorts}.
     *
     * <p>Implements FR9, D8 of add-manual-run.
     *
     * @param context the task context the dispatched outcome was produced from; never null
     * @param outcome the terminal outcome to dispatch; never null
     * @return the context/state pair to resume the engine with, or {@code null} to stop
     */
    @Nullable
    Resumption dispatch(TaskContext context, TaskOutcome outcome) {
        return switch (outcome) {
            case TaskOutcome.Completed completed -> {
                handleCompleted(context, completed);
                yield null;
            }
            case TaskOutcome.Paused paused -> handlePaused(context, paused);
            case TaskOutcome.Escalated escalated -> handleEscalated(context, escalated);
            case TaskOutcome.Aborted aborted -> {
                handleAborted(context, aborted);
                yield null;
            }
        };
    }

    /**
     * Delegates to {@link EscalationResumeDialog#renderEscalation}; kept here as the public
     * entry point tests and callers already use.
     *
     * <p>Implements FR9, D8 of add-manual-run.
     *
     * @param report the escalation reason to render; never null
     * @return the rendered text block; never null, never blank
     */
    String renderEscalation(EscalationReport report) {
        return EscalationResumeDialog.renderEscalation(report);
    }

    /**
     * Renders a final status summary for a {@code Completed} run and prints it (FR9): exit 0 is
     * Spring Boot's default for a returning runner, so this neither throws nor calls {@link
     * System#exit}. {@code currentStage}/{@code attemptLimit} are {@code null} (pipeline
     * finished); no live activity is known this late, so {@code LiveActivity.idle()} stands in.
     *
     * <p>Implements FR9, D10 of add-manual-run.
     */
    private void handleCompleted(TaskContext context, TaskOutcome.Completed completed) {
        var report = StatusReport.build(context, completed.finalState(), null, LiveActivity.idle());
        console.print(statusRenderer.renderFull(report));
    }

    /**
     * Resumes a {@code manual} checkpoint (D8, FR9): confirmation only — {@code paused} is
     * already advanced past the stage that triggered the pause, so there is nothing to reset
     * and no decision to append. No {@code inputExhausted()} pre-check ahead of this prompt
     * (contrast {@link #handleEscalated}): a {@code Paused} outcome means the checks genuinely
     * passed, so a live EOF here is always Case 2 (deliberate Ctrl-D at this very prompt) —
     * caught and rethrown as {@link CheckpointEofException} (exit 11), distinct from the
     * escalation resume prompt's {@link EscalationEofException} (exit 10).
     *
     * <p>Implements FR9, D8, D10 of add-manual-run.
     */
    private Resumption handlePaused(TaskContext context, TaskOutcome.Paused paused) {
        console.print("Stage '" + paused.passedStage() + "' passed. Manual checkpoint reached.");
        try {
            console.prompt("Press Enter to continue: ");
        } catch (ConsoleClosedException closed) {
            throw new CheckpointEofException(closed);
        }
        return new Resumption(context, paused.finalState());
    }

    /**
     * Routes an {@code Escalated} outcome to {@link EscalationResumeDialog}, which handles the
     * internal-error special case, the Case-1 EOF short-circuit, and the resumable dialog.
     *
     * <p>Implements FR9, D8 of add-manual-run.
     */
    @Nullable
    private Resumption handleEscalated(TaskContext context, TaskOutcome.Escalated escalated) {
        return escalationDialog.handle(context, escalated);
    }

    /**
     * Reports a broken durability guarantee (D8, FR9): {@code aborted.finalState()} never
     * reached durable storage, so there is nothing to resume from. Prints the cause and an
     * unpersisted-state summary to {@link System#err} (not the dialog console — this is a
     * terminal error path), then throws {@link AbortedException} so the CLI boundary can tell
     * this apart from {@code Completed} and route it to exit 12.
     *
     * <p>Implements FR9, D8, D10 of add-manual-run.
     */
    private void handleAborted(TaskContext context, TaskOutcome.Aborted aborted) {
        var finalState = aborted.finalState();
        var failedAt = aborted.failedAt();
        System.err.println("Aborted: " + aborted.cause());
        System.err.println("Task '" + context.taskId() + "': the round at stage '" + failedAt.stage()
                + "', attempt " + failedAt.attempt() + " was not persisted. Last known state: position="
                + finalState.position() + ", attemptsUsed=" + finalState.attemptsUsed() + ", "
                + finalState.attempts().size() + " attempt(s) recorded in this stage.");
        throw new AbortedException(aborted.cause());
    }

    /**
     * The context/state pair {@link #run} resumes the engine with after a handled outcome
     * (currently only the {@code Escalated} resume dialog and the {@code Paused} checkpoint
     * produce one).
     *
     * @param context the (possibly decision-appended) task context to resume with; never null
     * @param state the reset task state to resume with; never null
     */
    record Resumption(TaskContext context, TaskState state) {}
}
