package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.console.ConsoleClosedException;
import com.github.oinsio.gnomish.app.port.git.PendingVerification;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.status.LiveActivity;
import com.github.oinsio.gnomish.status.StatusReport;
import java.nio.file.Path;
import java.time.Clock;

/**
 * The per-outcome resume flows of {@link ContainerResumeRunner} — {@code null} (interrupted
 * visit), {@code escalated}, {@code paused}, and {@code completed}. Extracted from {@link
 * ContainerResumeRunner} for file size; the behavior is unchanged, drawing every collaborator from
 * the passed-in runner exactly as {@code ContainerResumeRunner.run} did.
 *
 * <p>Implements FR6, FR17, FR21, FR25 of add-sandbox-core.
 */
final class ContainerResumeOutcomes {

    private ContainerResumeOutcomes() {}

    /**
     * Outcome {@code null}: an interrupted visit. A snapshot commit unrecorded in {@code
     * state.json} is an interrupted verification (FR21) — no salvage runs, the round is complete
     * on the branch. Otherwise the environment is reattached (start stopped box, recreate over a
     * surviving volume, or fresh clone) and uncommitted leftovers are salvaged in-box; {@code
     * --discard-work} instead disposes whatever survives so the next materialize seeds a fresh
     * clone at the recorded tip.
     */
    static void resumeFromRecordedPosition(
            ContainerResumeRunner runner,
            SandboxRunSupport support,
            PipelineDefinition definition,
            TaskRecord taskJson,
            TaskState state,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            Path cloneDir) {
        PendingVerification pending = support.pendingVerification().orElse(null);
        if (discardWork) {
            support.disposeExistingEnvironment();
        } else if (state.position() instanceof Position.AtStage(String stage)) {
            // Reattach now (start stopped box / recreate over volume / fresh clone) so both the
            // salvage below and same-box verification of a pending snapshot have a live box.
            support.reattachFor(stage);
            if (pending == null) {
                support.salvageLeftovers(taskJson.context().taskId());
            }
        }
        ContainerTerminalDrive.run(
                runner.assembly, support, definition, taskJson.context(), state, interactiveMode, cloneDir, pending);
    }

    /** Outcome {@code escalated}: the same dialog the in-process path uses (UX2), decision committed factory-side. */
    static void resumeEscalated(
            ContainerResumeRunner runner,
            SandboxRunSupport support,
            PipelineDefinition definition,
            TaskRecord taskJson,
            TaskState state,
            RunArguments.InteractiveMode interactiveMode,
            Path cloneDir) {
        EscalationReport report = taskJson.lastEscalation();
        if (report == null) {
            throw new InternalErrorException("task \"" + taskJson.context().taskId()
                    + "\" has outcome \"escalated\" but no lastEscalation recorded in task.json — cannot resume");
        }
        var escalated = new TaskOutcome.Escalated(state, report);

        var console = runner.assembly.dialogConsole(taskJson.context(), state);
        var dialog = new EscalationResumeDialog(console, Clock.systemUTC());
        RunnerOutcomeLoop.Resumption resumption = dialog.handle(taskJson.context(), escalated);

        int before = taskJson.context().decisions().size();
        int after = resumption.context().decisions().size();
        if (after > before) {
            // Committed factory-side over bare objects, before any environment materializes —
            // the in-box clone then contains the decision from the start (FR25, D19).
            support.taskRepository()
                    .appendDecision(
                            taskJson.context().taskId(),
                            resumption.context().decisions().get(after - 1),
                            resumption.state());
        }
        ContainerTerminalDrive.run(
                runner.assembly,
                support,
                definition,
                resumption.context(),
                resumption.state(),
                interactiveMode,
                cloneDir,
                null);
    }

    /** Outcome {@code paused}: the same checkpoint confirmation as the host path (UX2). */
    static void resumePaused(
            ContainerResumeRunner runner,
            SandboxRunSupport support,
            PipelineDefinition definition,
            TaskRecord taskJson,
            TaskState state,
            String passedStage,
            RunArguments.InteractiveMode interactiveMode,
            Path cloneDir) {
        var console = runner.assembly.dialogConsole(taskJson.context(), state);
        console.print("Stage '" + passedStage + "' passed. Manual checkpoint reached.");
        try {
            console.prompt("Press Enter to continue: ");
        } catch (ConsoleClosedException closed) {
            throw new CheckpointEofException(closed);
        }
        ContainerTerminalDrive.run(
                runner.assembly, support, definition, taskJson.context(), state, interactiveMode, cloneDir, null);
    }

    /** Outcome {@code completed}: the same final status summary as the host path, no engine run. */
    static void reportCompleted(ContainerResumeRunner runner, TaskRecord taskJson, TaskState state) {
        var report = StatusReport.build(taskJson.context(), state, null, LiveActivity.idle());
        System.out.println(runner.statusRenderer.renderFull(report));
    }
}
