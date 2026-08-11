package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.SandboxProperties;
import com.github.oinsio.gnomish.adapter.console.ConsoleClosedException;
import com.github.oinsio.gnomish.adapter.environment.Segment;
import com.github.oinsio.gnomish.adapter.git.ContainerResumeBranch;
import com.github.oinsio.gnomish.adapter.git.FactoryCloneHardening;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.SnapshotTipCheck;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonContent;
import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.status.LiveActivity;
import com.github.oinsio.gnomish.status.StatusReport;
import com.github.oinsio.gnomish.status.StatusTextRenderer;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * The container-mode counterpart of {@link GitResumeRunner} (FR6, the
 * integration pass of add-sandbox-core): {@code --resume} of a sandboxed task
 * from the branch alone — no worktree exists or is created. Bootstrap locates
 * and reconciles the local branch on refs ({@link ContainerResumeBranch}),
 * reads {@code task.json}/{@code state.json} as bare git objects (FR17), and
 * the outcome switch mirrors the host continuation byte-for-byte (UX2): {@code
 * escalated} runs the same {@link EscalationResumeDialog}; {@code paused} the
 * same checkpoint confirmation; {@code null} salvages the interrupted round
 * in-box (or {@code --discard-work} disposes and re-materializes fresh) and
 * continues; {@code completed} reports. A snapshot commit found unrecorded at
 * the tip resumes as an interrupted verification — re-verified against exactly
 * that attempt commit, no agent re-run, no attempt burned (FR21, D15). The
 * resume decision is committed factory-side before any environment
 * materializes, so the in-box clone contains it from the start (FR25, D19).
 *
 * <p>Implements FR6, FR17, FR21, FR25 of add-sandbox-core.
 */
final class ContainerResumeRunner {

    private final ManualRunAssembly assembly;
    private final SandboxProperties sandboxProperties;
    private final FactoryProperties factoryProperties;
    private final String taskIdMdcKey;
    private final ContainerSupportFactory supportFactory;
    private final StatusTextRenderer statusRenderer = new StatusTextRenderer();

    /** Production wiring: per-run support built by {@link ContainerRunSupport#create}. */
    ContainerResumeRunner(
            ManualRunAssembly assembly,
            SandboxProperties sandboxProperties,
            FactoryProperties factoryProperties,
            String taskIdMdcKey) {
        this(assembly, sandboxProperties, factoryProperties, taskIdMdcKey, ContainerRunSupport::create);
    }

    /**
     * Seam constructor ({@link ContainerSupportFactory}): daemon-free specs bind a factory whose
     * environments run over a scripted fake docker CLI; behavior is otherwise identical.
     */
    ContainerResumeRunner(
            ManualRunAssembly assembly,
            SandboxProperties sandboxProperties,
            FactoryProperties factoryProperties,
            String taskIdMdcKey,
            ContainerSupportFactory supportFactory) {
        this.assembly = assembly;
        this.sandboxProperties = sandboxProperties;
        this.factoryProperties = factoryProperties;
        this.taskIdMdcKey = taskIdMdcKey;
        this.supportFactory = supportFactory;
    }

    /**
     * Resumes the sandboxed task named by {@code taskId} to a terminal boundary.
     *
     * @throws UsageException if no branch for {@code taskId} is found, or its last recorded
     *     outcome is Aborted
     */
    void run(
            Path cloneDir,
            String taskId,
            PipelineDefinition definition,
            List<Segment> segments,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork) {
        GitProcessRunner runner = new GitProcessRunner();
        new FactoryCloneHardening(runner).harden(cloneDir);
        if (!new ContainerResumeBranch(runner).ensureLocalBranch(cloneDir, taskId)) {
            throw new UsageException("no task branch found for \"" + taskId
                    + "\" — locally, as a remote-tracking ref, or on origin; nothing to resume");
        }

        var support =
                supportFactory.create(cloneDir, taskId, segments, sandboxProperties, factoryProperties, List.of());
        TaskJsonContent taskJson = support.readTaskJson();
        String recordedTaskId = taskJson.context().taskId();
        MDC.put(taskIdMdcKey, recordedTaskId);
        TaskState state =
                support.readStateOrInitial(definition.stages().getFirst().name());

        TaskOutcomeDto outcome = taskJson.outcome();
        if (outcome == null) {
            resumeFromRecordedPosition(support, definition, taskJson, state, interactiveMode, discardWork, cloneDir);
            return;
        }
        switch (outcome) {
            case TaskOutcomeDto.Completed ignored -> reportCompleted(taskJson, state);
            case TaskOutcomeDto.Escalated ignored ->
                resumeEscalated(support, definition, taskJson, state, interactiveMode, cloneDir);
            case TaskOutcomeDto.Paused paused ->
                resumePaused(support, definition, taskJson, state, paused.passedStage(), interactiveMode, cloneDir);
            case TaskOutcomeDto.Aborted ignored ->
                throw new UsageException("cannot resume task \"" + recordedTaskId
                        + "\": its last recorded outcome is Aborted — inspect the kept task environment and start a"
                        + " new task instead");
        }
    }

    /**
     * Outcome {@code null}: an interrupted visit. A snapshot commit unrecorded in {@code
     * state.json} is an interrupted verification (FR21) — no salvage runs, the round is complete
     * on the branch. Otherwise the environment is reattached (start stopped box, recreate over a
     * surviving volume, or fresh clone) and uncommitted leftovers are salvaged in-box; {@code
     * --discard-work} instead disposes whatever survives so the next materialize seeds a fresh
     * clone at the recorded tip.
     */
    private void resumeFromRecordedPosition(
            ContainerRunSupport support,
            PipelineDefinition definition,
            TaskJsonContent taskJson,
            TaskState state,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            Path cloneDir) {
        SnapshotTipCheck.@Nullable InterruptedVerification pending =
                support.snapshotTipCheck().inspect(support.branch()).orElse(null);
        if (discardWork) {
            support.disposeExistingEnvironment();
        } else if (state.position() instanceof Position.AtStage(String stage)) {
            // Reattach now (start stopped box / recreate over volume / fresh clone) so both the
            // salvage below and same-box verification of a pending snapshot have a live box.
            support.lease().environmentFor(stage);
            if (pending == null) {
                support.salvage().salvage(taskJson.context().taskId());
            }
        }
        ContainerTerminalDrive.run(
                assembly, support, definition, taskJson.context(), state, interactiveMode, cloneDir, pending);
    }

    /** Outcome {@code escalated}: the same dialog the in-process path uses (UX2), decision committed factory-side. */
    private void resumeEscalated(
            ContainerRunSupport support,
            PipelineDefinition definition,
            TaskJsonContent taskJson,
            TaskState state,
            RunArguments.InteractiveMode interactiveMode,
            Path cloneDir) {
        EscalationReport report = taskJson.lastEscalation();
        if (report == null) {
            throw new InternalErrorException("task \"" + taskJson.context().taskId()
                    + "\" has outcome \"escalated\" but no lastEscalation recorded in task.json — cannot resume");
        }
        var escalated = new TaskOutcome.Escalated(state, report);

        var console = assembly.dialogConsole(taskJson.context(), state);
        var dialog = new EscalationResumeDialog(console, Clock.systemUTC());
        RunnerOutcomeLoop.Resumption resumption = dialog.handle(taskJson.context(), escalated);
        if (resumption == null) {
            return;
        }

        int before = taskJson.context().decisions().size();
        int after = resumption.context().decisions().size();
        if (after > before) {
            // Committed factory-side over bare objects, before any environment materializes —
            // the in-box clone then contains the decision from the start (FR25, D19).
            support.taskRepository()
                    .appendDecision(
                            taskJson.context().taskId(),
                            resumption.context().decisions().get(after - 1));
        }
        ContainerTerminalDrive.run(
                assembly,
                support,
                definition,
                resumption.context(),
                resumption.state(),
                interactiveMode,
                cloneDir,
                null);
    }

    /** Outcome {@code paused}: the same checkpoint confirmation as the host path (UX2). */
    private void resumePaused(
            ContainerRunSupport support,
            PipelineDefinition definition,
            TaskJsonContent taskJson,
            TaskState state,
            String passedStage,
            RunArguments.InteractiveMode interactiveMode,
            Path cloneDir) {
        var console = assembly.dialogConsole(taskJson.context(), state);
        console.print("Stage '" + passedStage + "' passed. Manual checkpoint reached.");
        try {
            console.prompt("Press Enter to continue: ");
        } catch (ConsoleClosedException closed) {
            throw new CheckpointEofException(closed);
        }
        ContainerTerminalDrive.run(
                assembly, support, definition, taskJson.context(), state, interactiveMode, cloneDir, null);
    }

    /** Outcome {@code completed}: the same final status summary as the host path, no engine run. */
    private void reportCompleted(TaskJsonContent taskJson, TaskState state) {
        var report = StatusReport.build(taskJson.context(), state, null, LiveActivity.idle());
        System.out.println(statusRenderer.renderFull(report));
    }
}
