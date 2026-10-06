package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.console.DialogConsole;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.domain.engine.Engine;
import com.github.oinsio.gnomish.domain.engine.EnginePorts;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.status.ReportPlane;
import com.github.oinsio.gnomish.status.StatusReport;
import com.github.oinsio.gnomish.status.StatusTextRenderer;
import org.jspecify.annotations.Nullable;

/**
 * The runner's outcome dispatch (design D8 of add-manual-run; D1 of make-run-headless): calls
 * {@link Engine#run} once and dispatches the returned {@link TaskOutcome} by an exhaustive switch —
 * no {@code default} arm — so a new variant fails to compile here until its branch is added.
 *
 * <p>It never reads the console (FR6 of make-run-headless). {@code Escalated} and {@code Paused}
 * are stops: their render — the report or the checkpoint sentence, then the return-path line — is
 * printed through {@link TerminalOutcomeRender}, and the outcome is returned to the terminal
 * boundary, which records the park and exits 10 or 11 through {@link RunParkedException}. {@code
 * Completed} prints a final status summary and is returned. {@code PipelineMismatch}
 * ("unreachable in-process") becomes {@link InternalErrorException}. {@code Aborted} is terminal:
 * {@link #handleAborted} prints to the error console then throws {@link AbortedException}, exit 12.
 *
 * <p>Implements FR9, D8, D10 of add-manual-run; FR1, FR2, FR6, FR7, FR8 of make-run-headless.
 */
public final class RunnerOutcomeLoop {

    private final Engine engine;
    private final DialogConsole console;
    private final ConsoleIO errorConsole;
    private final StatusTextRenderer statusRenderer = new StatusTextRenderer();

    /**
     * @param engine the pure orchestrator this loop drives; never null
     * @param console the console owner the stop renders and the final status are printed through;
     *     never null
     * @param errorConsole the console owner bound to {@code stderr} — the terminal error path,
     *     deliberately not the dialog console (FR5, FR6 of harden-untrusted-text-sinks); never null
     */
    public RunnerOutcomeLoop(Engine engine, DialogConsole console, ConsoleIO errorConsole) {
        this.engine = engine;
        this.console = console;
        this.errorConsole = errorConsole;
    }

    /**
     * Runs {@code definition}/{@code context} from {@code initialState} through the engine and
     * dispatches the outcome via {@link #dispatch}, returning it for the terminal boundary to settle.
     *
     * <p>Implements FR9, D8 of add-manual-run; FR1, FR2, FR7 of make-run-headless.
     *
     * @param definition the pipeline the run advances through; never null
     * @param context the task's identity and human decisions; never null
     * @param initialState the state the engine resumes from; never null
     * @param workspace the opaque working copy the run operates on; never null
     * @param ports the collaborators the engine drives; never null
     * @param returnPath where a stopped task resumes from, printed after the stop render; {@code
     *     null} in in-place mode, which has nothing to resume
     * @return the terminal outcome: {@code Completed}, {@code Paused} or {@code Escalated} — never
     *     {@code Aborted}, which throws
     */
    public TaskOutcome run(
            PipelineDefinition definition,
            TaskContext context,
            TaskState initialState,
            Workspace workspace,
            EnginePorts ports,
            TerminalOutcomeRender.@Nullable ReturnPath returnPath) {
        return dispatch(context, engine.run(definition, context, initialState, workspace, ports), returnPath);
    }

    /**
     * Dispatches one {@link TaskOutcome} by an exhaustive switch over its four sealed variants
     * (design D8). Extracted from {@link #run} so tests can exercise dispatch directly, without
     * constructing a full {@link EnginePorts}.
     *
     * <p>Implements FR9, D8 of add-manual-run; FR1, FR2 of make-run-headless.
     *
     * @param context the task context the dispatched outcome was produced from; never null
     * @param outcome the terminal outcome to dispatch; never null
     * @param returnPath where a stopped task resumes from; {@code null} in in-place mode
     * @return {@code outcome}, once rendered
     */
    TaskOutcome dispatch(
            TaskContext context, TaskOutcome outcome, TerminalOutcomeRender.@Nullable ReturnPath returnPath) {
        switch (outcome) {
            case TaskOutcome.Completed completed -> handleCompleted(context, completed);
            case TaskOutcome.Paused paused ->
                console.print(TerminalOutcomeRender.paused(paused.passedStage(), returnPath) + ConsoleIO.LINE_END);
            case TaskOutcome.Escalated escalated -> handleEscalated(escalated, returnPath);
            case TaskOutcome.Aborted aborted -> handleAborted(context, aborted);
        }
        return outcome;
    }

    /**
     * Renders a final status summary for a {@code Completed} run and prints it (FR9).
     * {@code currentStage} is {@code null} (pipeline finished); the report is built from the
     * final state alone, with no recorded escalation or outcome.
     *
     * <p>Implements FR9, D10 of add-manual-run.
     */
    private void handleCompleted(TaskContext context, TaskOutcome.Completed completed) {
        var report = StatusReport.build(context, completed.finalState(), null, null);
        console.print(statusRenderer.renderFull(report));
    }

    /**
     * Prints an {@code Escalated} stop: the report and the return-path line (FR1 of
     * make-run-headless). A {@code PipelineMismatch} cannot arise in-process and is an internal
     * error instead, carrying the report as its message.
     *
     * <p>Implements FR9, D8 of add-manual-run; FR1 of make-run-headless.
     */
    private void handleEscalated(
            TaskOutcome.Escalated escalated, TerminalOutcomeRender.@Nullable ReturnPath returnPath) {
        if (escalated.report() instanceof EscalationReport.PipelineMismatch) {
            throw new InternalErrorException(
                    TerminalOutcomeRender.renderEscalation(escalated.report(), ReportPlane.CONSOLE));
        }
        console.print(TerminalOutcomeRender.escalated(escalated.report(), returnPath) + ConsoleIO.LINE_END);
    }

    /**
     * Reports a broken durability guarantee (D8, FR9): {@code aborted.finalState()} never
     * reached durable storage, so there is nothing to resume from. Prints the cause and an
     * unpersisted-state summary to the error console (not the dialog console — this is a
     * terminal error path), then throws {@link AbortedException} so the CLI boundary can tell
     * this apart from {@code Completed} and route it to exit 12.
     *
     * <p>Implements FR9, D8, D10 of add-manual-run.
     */
    private void handleAborted(TaskContext context, TaskOutcome.Aborted aborted) {
        var finalState = aborted.finalState();
        var failedAt = aborted.failedAt();
        // The console exit, explicitly (design D6 of type-untrusted-text): an operator reading an
        // abort needs the whole rendered chain with its line structure, which is exactly what the
        // log exit would take away — one line, tail only.
        errorConsole.print("Aborted: " + aborted.cause().forConsole() + ConsoleIO.LINE_END);
        errorConsole.print("Task '" + context.taskId() + "': the round at stage '" + failedAt.stage()
                + "', attempt " + failedAt.attempt() + " was not persisted. Last known state: position="
                + finalState.position() + ", attemptsUsed=" + finalState.attemptsUsed() + ", "
                + finalState.attempts().size() + " attempt(s) recorded in this stage." + ConsoleIO.LINE_END);
        throw new AbortedException(aborted);
    }

    /**
     * The context/state pair a resumed escalation continues the engine with (the resume
     * continuations' escalated arm).
     *
     * @param context the (possibly decision-appended) task context to resume with; never null
     * @param state the reset task state to resume with; never null
     */
    record Resumption(TaskContext context, TaskState state) {}
}
