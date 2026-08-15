package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.console.DialogConsole;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.status.ConsoleStatusRenderer;
import com.github.oinsio.gnomish.status.SnapshotActivityTracker;
import com.github.oinsio.gnomish.status.StatusSnapshotHolder;
import com.github.oinsio.gnomish.status.StatusTextRenderer;

/**
 * Builds a standalone {@link DialogConsole} for a resume dialog that runs before any {@link
 * RunAssembly#assemble} call (design D9, task 4.7 of add-git-workflow): {@link
 * GitResumeRunner}'s escalated-decision and paused-confirmation dialogs need the exact same
 * console construction a live run uses (UX2: "same questions, same feel") without yet having an
 * {@code EnginePorts}/{@code RunnerOutcomeLoop} pair to hang it off of — the subsequent {@link
 * RunAssembly#assemble} call (once the dialog resolves) builds its own fresh console the
 * same way, just as {@link GitModeRunner} always did for a fresh run. Extracted from {@link
 * RunAssembly} purely to keep both files within the project's file-size guidance
 * (`.claude/rules/process-invariants.md`).
 *
 * <p>Implements FR8, UX2 of add-git-workflow.
 */
final class ResumeDialogConsoleFactory {

    private ResumeDialogConsoleFactory() {}

    /**
     * Builds the console.
     *
     * @param systemConsoleIO the real stdin/stdout console I/O, shared with a live run's own
     *     console construction
     * @param systemClock backs the returned console's activity tracker, matching {@link
     *     RunAssembly#assemble}'s own wiring
     * @param context the resumed task's identity and decisions, for the console's {@code status}
     *     meta-command; never null
     * @param state the resumed task's current state, seeding the status snapshot the console's
     *     {@code status}/{@code status --json} meta-commands render from; never null
     * @return a fresh {@link DialogConsole} wired the same way {@link RunAssembly#assemble}
     *     wires its own
     */
    static DialogConsole build(
            ConsoleIO systemConsoleIO, SystemClock systemClock, TaskContext context, TaskState state) {
        var holder = new StatusSnapshotHolder(state, definitionlessAttemptLimit());
        var statusRenderer = new ConsoleStatusRenderer(holder, context, new StatusTextRenderer());
        var activityTracker = new SnapshotActivityTracker(holder, systemClock);
        return new DialogConsole(systemConsoleIO, statusRenderer, activityTracker);
    }

    /**
     * The attempt-limit placeholder {@link #build} seeds its {@link StatusSnapshotHolder} with: no
     * {@link PipelineDefinition} is available yet at dialog time (unlike {@link
     * RunAssembly#assemble}, which resolves the real limit), and the dialog's {@code status}
     * meta-command is a convenience, not the report of record — the real limit is visible again
     * once the subsequent {@link RunAssembly#assemble} call runs.
     */
    private static int definitionlessAttemptLimit() {
        return 0;
    }
}
