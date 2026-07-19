package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.console.ConsoleClosedException;
import com.github.oinsio.gnomish.adapter.console.DialogConsole;
import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository;
import com.github.oinsio.gnomish.adapter.git.WorktreeSalvage;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.status.LiveActivity;
import com.github.oinsio.gnomish.status.StatusReport;
import com.github.oinsio.gnomish.status.StatusTextRenderer;
import java.nio.file.Path;
import java.time.Clock;

/**
 * The four outcome-driven continuation paths {@link GitResumeRunner#run} switches on: {@link
 * #resumeFromRecordedPosition}, {@link #resumeEscalated}, {@link #resumePaused}, {@link
 * #reportCompleted}. Extracted from {@link GitResumeRunner} purely to keep both files within the
 * project's file-size guidance (`.claude/rules/process-invariants.md`).
 *
 * <p>The three engine-rerunning paths share {@link #runToTerminalBoundary}, mirroring {@link
 * GitModeRunner}'s terminal-boundary handling; {@code Escalated}/{@code Paused} are never
 * observed here, since the loop resolves them in-process via its own dialogs first.
 *
 * <p>Implements FR5, FR8, UX2 of add-git-workflow.
 */
final class GitResumeContinuation {

    private final ManualRunAssembly assembly;
    private final GitProcessRunner runner;
    private final GitTaskRepository taskRepository;
    private final Path cloneDir;
    private final ResumeBootstrap bootstrap;
    private final StatusTextRenderer statusRenderer = new StatusTextRenderer();

    GitResumeContinuation(
            ManualRunAssembly assembly,
            GitProcessRunner runner,
            GitTaskRepository taskRepository,
            Path cloneDir,
            ResumeBootstrap bootstrap) {
        this.assembly = assembly;
        this.runner = runner;
        this.taskRepository = taskRepository;
        this.cloneDir = cloneDir;
        this.bootstrap = bootstrap;
    }

    /**
     * Outcome {@code null} ("process died mid-visit"): reconciles uncommitted leftovers of the
     * interrupted round, then continues the engine loop from {@code state.json}'s recorded
     * position, with no dialog. Divergence reconciliation already ran in {@link
     * GitResumeRunner#bootstrap} — the worktree's {@code HEAD} is exactly the last round (or
     * salvage) commit, so anything still dirty is the interrupted round's leftovers. Default
     * ({@code discardWork} false): {@link WorktreeSalvage#salvage} commits the leftovers as-is
     * (not a round, never recorded in {@code state.json}) so the next round's gnome sees the
     * half-done work and the QC loop judges it. {@code --discard-work}: {@link
     * WorktreeSalvage#discard} resets to {@code HEAD}, so the loop replays the round clean.
     *
     * <p>Implements FR8, FR10 of add-git-workflow.
     */
    void resumeFromRecordedPosition(
            PipelineDefinition definition,
            TaskState finalState,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork) {
        var salvage = new WorktreeSalvage(runner, bootstrap.worktreePath());
        if (discardWork) {
            salvage.discard();
        } else {
            salvage.salvage(bootstrap.taskId());
        }
        runToTerminalBoundary(definition, bootstrap.context(), finalState, interactiveMode);
    }

    /**
     * Outcome {@code escalated}: rebuilds the domain {@link TaskOutcome.Escalated} from {@code
     * finalState} and {@link ResumeBootstrap#lastEscalation()}, then routes it through {@link
     * EscalationResumeDialog#handle} — the same class {@link RunnerOutcomeLoop} calls in-process,
     * so question, resume prompt, and EOF handling are byte-for-byte identical (UX2). A non-blank
     * answer is appended as a Decision via {@link GitTaskRepository#appendDecision} — also
     * resetting {@code outcome} to null in the same commit (FR5) — before the engine loop resumes
     * from the dialog's reset state.
     *
     * @throws InternalErrorException if {@code task.json} recorded outcome {@code escalated} with
     *     no {@code lastEscalation} — a state {@link GitTaskRepository#recordOutcome} never
     *     produces (always populates both together, FR5), so this can only mean a corrupted branch
     *
     * <p>Implements FR5, FR8, UX2 of add-git-workflow.
     */
    void resumeEscalated(
            PipelineDefinition definition, TaskState finalState, RunArguments.InteractiveMode interactiveMode) {
        EscalationReport report = bootstrap.lastEscalation();
        if (report == null) {
            throw new InternalErrorException("task \"" + bootstrap.taskId()
                    + "\" has outcome \"escalated\" but no lastEscalation recorded in task.json — cannot resume");
        }
        var escalated = new TaskOutcome.Escalated(finalState, report);

        DialogConsole console = assembly.dialogConsole(bootstrap.context(), finalState);
        var dialog = new EscalationResumeDialog(console, Clock.systemUTC());
        RunnerOutcomeLoop.Resumption resumption = dialog.handle(bootstrap.context(), escalated);
        if (resumption == null) {
            return;
        }

        recordDecisionIfAppended(resumption.context());
        runToTerminalBoundary(definition, resumption.context(), resumption.state(), interactiveMode);
    }

    /**
     * Outcome {@code paused}: a checkpoint confirmation mirroring {@link
     * RunnerOutcomeLoop#handlePaused} — "Press Enter to continue", nothing to reset, no decision
     * appended, since a manual pause is not a question. Resumes from {@code finalState} directly
     * (already advanced past the paused stage).
     *
     * <p>Implements FR8, UX2 of add-git-workflow.
     */
    void resumePaused(
            PipelineDefinition definition,
            TaskState finalState,
            String passedStage,
            RunArguments.InteractiveMode interactiveMode) {
        DialogConsole console = assembly.dialogConsole(bootstrap.context(), finalState);
        console.print("Stage '" + passedStage + "' passed. Manual checkpoint reached.");
        try {
            console.prompt("Press Enter to continue: ");
        } catch (ConsoleClosedException closed) {
            throw new CheckpointEofException(closed);
        }
        runToTerminalBoundary(definition, bootstrap.context(), finalState, interactiveMode);
    }

    /**
     * Outcome {@code completed}: prints the same final status summary {@link
     * RunnerOutcomeLoop#handleCompleted} prints in-process, and returns — no engine run, no
     * further worktree or branch write.
     *
     * <p>Implements FR8, UX2 of add-git-workflow.
     */
    void reportCompleted(TaskState finalState) {
        var report = StatusReport.build(bootstrap.context(), finalState, null, LiveActivity.idle());
        System.out.println(statusRenderer.renderFull(report));
    }

    /**
     * Shared tail for every path that re-runs the engine: assembles a fresh {@link
     * GitAttemptPersistence} rooted at the worktree, runs {@link RunnerOutcomeLoop}, then records
     * the new terminal outcome — {@code Completed} (read back from {@code state.json}) or {@code
     * Aborted} (from the caught {@link AbortedException}) — through {@link GitOutcomeRecorder}.
     */
    private void runToTerminalBoundary(
            PipelineDefinition definition,
            TaskContext context,
            TaskState state,
            RunArguments.InteractiveMode interactiveMode) {
        Path worktree = bootstrap.worktreePath();
        var persistence = new GitAttemptPersistence(runner, worktree, bootstrap.taskId());
        var workspace = new DirectoryWorkspace(worktree);
        var assembled = assembly.assemble(definition, context, state, interactiveMode, persistence);

        try {
            assembled.loop().run(definition, context, state, workspace, assembled.ports());
        } catch (AbortedException aborted) {
            TaskOutcome.Aborted outcome = aborted.outcome();
            if (outcome != null) {
                GitOutcomeRecorder.recordAndCleanUp(
                        runner, taskRepository, cloneDir, worktree, bootstrap.taskId(), outcome);
            }
            throw aborted;
        }

        TaskOutcome.Completed completed = new TaskOutcome.Completed(GitFreshTaskSupport.readFinalState(worktree));
        GitOutcomeRecorder.recordAndCleanUp(runner, taskRepository, cloneDir, worktree, bootstrap.taskId(), completed);
    }

    /**
     * Appends a resume {@link com.github.oinsio.gnomish.domain.engine.Decision} through {@link
     * GitTaskRepository#appendDecision} when the escalation dialog added one to {@code
     * resumedContext} (also resets {@code outcome} to null in the same commit, FR5). A blank
     * answer resumes without a decision — detected by comparing decision-list sizes against the
     * bootstrap's original context, since the dialog only returns the possibly-appended {@link
     * TaskContext}, not a boolean flag.
     */
    private void recordDecisionIfAppended(TaskContext resumedContext) {
        int before = bootstrap.context().decisions().size();
        int after = resumedContext.decisions().size();
        if (after > before) {
            taskRepository.appendDecision(
                    bootstrap.taskId(), resumedContext.decisions().get(after - 1));
        }
    }
}
