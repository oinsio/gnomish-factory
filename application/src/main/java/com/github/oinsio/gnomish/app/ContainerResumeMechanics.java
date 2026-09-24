package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
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
        branch.support().confirmTerminalWrite();
    }

    @Override
    public void finishCleanup(Path cloneDir, ContainerResumeBootstrap branch) {
        branch.support().finishCleanup();
    }

    @Override
    public TakeResult resumeWithoutDecision(TakeOrder order, ContainerResumeBootstrap branch, TaskState finalState) {
        return resumeRunner.resumeWithoutDecision(order, branch, finalState);
    }

    @Override
    public TaskContext appendDecision(
            Path cloneDir,
            ContainerResumeBootstrap branch,
            TaskState finalState,
            TaskState resetState,
            String decisionText) {
        return resumeRunner.appendDecision(branch, finalState, resetState, decisionText);
    }

    @Override
    public TakeResult resumeDecided(
            TakeOrder order, ContainerResumeBootstrap branch, TaskContext context, TaskState resetState) {
        return resumeRunner.resumeDecided(order, branch, context, resetState);
    }
}
