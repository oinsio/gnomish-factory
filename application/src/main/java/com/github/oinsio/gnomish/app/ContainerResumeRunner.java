package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.Segment;
import com.github.oinsio.gnomish.status.StatusTextRenderer;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.MDC;

/**
 * The container-mode counterpart of {@link GitResumeRunner} (FR6, the
 * integration pass of add-sandbox-core): {@code --resume} of a sandboxed task
 * from the branch alone — no worktree exists or is created. Bootstrap locates
 * and reconciles the local branch on refs (the task-branch port),
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

    final RunAssembly assembly;
    private final TaskGit git;
    private final SandboxProperties sandboxProperties;
    private final FactoryProperties factoryProperties;
    private final String taskIdMdcKey;
    private final ContainerSupportFactory supportFactory;
    final StatusTextRenderer statusRenderer = new StatusTextRenderer();

    /**
     * The support factory is injected ({@link ContainerSupportFactory}): the composition root binds
     * the real container bundle, daemon-free specs bind one whose environments run over a scripted
     * fake docker CLI; behavior is otherwise identical.
     */
    ContainerResumeRunner(
            RunAssembly assembly,
            TaskGit git,
            SandboxProperties sandboxProperties,
            FactoryProperties factoryProperties,
            String taskIdMdcKey,
            ContainerSupportFactory supportFactory) {
        this.assembly = assembly;
        this.git = git;
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
        git.branches().harden(cloneDir);
        if (!git.branches().ensureLocalTaskBranch(cloneDir, taskId)) {
            throw new UsageException("no task branch found for \"" + taskId
                    + "\" — locally, as a remote-tracking ref, or on origin; nothing to resume");
        }
        // Resume-start touchpoint (FR3 of fix-lifecycle-push): the reconcile above brings local up
        // to what origin holds; this pushes origin up to what local holds, delivering a commit an
        // earlier instance recorded but never got pushed. Best-effort, never blocking.
        git.branches().reconcileRemote(cloneDir, taskId, "resume-start");

        var support = supportFactory.create(
                cloneDir, taskId, segments, sandboxProperties, factoryProperties, definition, List.of());
        TaskRecord taskJson = support.readTaskJson();
        String recordedTaskId = taskJson.context().taskId();
        MDC.put(taskIdMdcKey, recordedTaskId);
        TaskState state =
                support.readStateOrInitial(definition.stages().getFirst().name());

        RecordedOutcome outcome = taskJson.outcome();
        if (outcome == null) {
            ContainerResumeOutcomes.resumeFromRecordedPosition(
                    this, support, definition, taskJson, state, interactiveMode, discardWork, cloneDir);
            return;
        }
        switch (outcome) {
            case RecordedOutcome.Completed ignored -> ContainerResumeOutcomes.reportCompleted(this, taskJson, state);
            case RecordedOutcome.Escalated ignored ->
                ContainerResumeOutcomes.resumeEscalated(
                        this, support, definition, taskJson, state, interactiveMode, cloneDir);
            case RecordedOutcome.Paused paused ->
                ContainerResumeOutcomes.resumePaused(
                        this, support, definition, taskJson, state, paused.passedStage(), interactiveMode, cloneDir);
            case RecordedOutcome.Aborted ignored ->
                throw new UsageException("cannot resume task \"" + recordedTaskId
                        + "\": its last recorded outcome is Aborted — inspect the kept task environment and start a"
                        + " new task instead");
        }
    }
}
