package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.SandboxProperties;
import com.github.oinsio.gnomish.adapter.environment.Segment;
import com.github.oinsio.gnomish.adapter.git.ContainerResumeBranch;
import com.github.oinsio.gnomish.adapter.git.FactoryCloneHardening;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonContent;
import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.status.StatusTextRenderer;
import java.nio.file.Path;
import java.util.List;
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

    final ManualRunAssembly assembly;
    private final SandboxProperties sandboxProperties;
    private final FactoryProperties factoryProperties;
    private final String taskIdMdcKey;
    private final ContainerSupportFactory supportFactory;
    final StatusTextRenderer statusRenderer = new StatusTextRenderer();

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
            ContainerResumeOutcomes.resumeFromRecordedPosition(
                    this, support, definition, taskJson, state, interactiveMode, discardWork, cloneDir);
            return;
        }
        switch (outcome) {
            case TaskOutcomeDto.Completed ignored -> ContainerResumeOutcomes.reportCompleted(this, taskJson, state);
            case TaskOutcomeDto.Escalated ignored ->
                ContainerResumeOutcomes.resumeEscalated(
                        this, support, definition, taskJson, state, interactiveMode, cloneDir);
            case TaskOutcomeDto.Paused paused ->
                ContainerResumeOutcomes.resumePaused(
                        this, support, definition, taskJson, state, paused.passedStage(), interactiveMode, cloneDir);
            case TaskOutcomeDto.Aborted ignored ->
                throw new UsageException("cannot resume task \"" + recordedTaskId
                        + "\": its last recorded outcome is Aborted — inspect the kept task environment and start a"
                        + " new task instead");
        }
    }
}
