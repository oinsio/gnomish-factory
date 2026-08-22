package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.Segment;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Container-mode {@link ResumeMechanics}: no worktree at all — the branch is read over bare git
 * objects, the environment is reattached (started, recreated over the surviving volume, or seeded
 * fresh) and leftovers are salvaged in-box, through the {@link
 * com.github.oinsio.gnomish.app.port.run.SandboxRunSupport} bundle {@link
 * TakeContainerResumeRunner} builds (design D8 of add-serve-sandbox-lifecycle).
 *
 * <p>Implements FR1, NFR-R4 of add-serve-sandbox-lifecycle; FR9, FR12, D3 of add-tracker-port.
 *
 * @param resumeRunner the sandbox-backed resume machinery; never null
 * @param segments this run's container-bound segment plan; never null
 * @param definition the pipeline this resume advances through; never null
 */
record ContainerResumeMechanics(
        TakeContainerResumeRunner resumeRunner, List<Segment> segments, PipelineDefinition definition)
        implements ResumeMechanics<ContainerResumeBootstrap> {

    @Override
    public @Nullable ContainerResumeBootstrap loadBranch(Path cloneDir, String taskId) {
        return resumeRunner.bootstrap(cloneDir, taskId, segments, definition);
    }

    @Override
    public TaskState readFinalState(ContainerResumeBootstrap branch) {
        return branch.support().readFinalState();
    }

    @Override
    public void confirmTerminalWrite(Path cloneDir, ContainerResumeBootstrap branch) {
        // Deliberately nothing: container mode's factory-side task repository has no
        // confirmTerminalWrite yet, so a container branch always reads as tracker-write-pending and
        // every future resume re-delivers the park. Safe and idempotent — the ClaimGuard pre-write
        // check makes a repeat delivery a no-op — it just never "settles" the way a host branch's
        // marker does. See ContainerResumeBootstrap#trackerWritePending.
    }

    @Override
    public TakeResult resumeWithoutDecision(
            Path cloneDir,
            ContainerResumeBootstrap branch,
            TaskState finalState,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        return resumeRunner.resumeWithoutDecision(
                cloneDir, branch, definition, finalState, interactiveMode, discardWork, tracker, ref, instanceId);
    }

    @Override
    public TakeResult resumeWithDecision(
            Path cloneDir,
            ContainerResumeBootstrap branch,
            TaskState finalState,
            @Nullable String decisionText,
            RunArguments.InteractiveMode interactiveMode,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        return resumeRunner.resumeWithDecision(
                cloneDir, branch, definition, finalState, decisionText, interactiveMode, tracker, ref, instanceId);
    }
}
