package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.TrackerWrite;
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
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
 * <p>Implements FR1, NFR-R4 of add-serve-sandbox-lifecycle; FR9, FR12, D3 of add-tracker-port; FR3,
 * FR4, FR7 of make-checkpoint-gate-durable (the approval, the resumed write and a gate's owed park
 * go through the bare-object lifecycle repository, before any environment is materialized).
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
    public TaskState approveCheckpoint(TakeOrder order, ContainerResumeBootstrap branch) {
        TaskState gated = readFinalState(branch);
        var support = branch.support();
        // The approval is a factory-side commit over bare objects: the kept box cannot learn of it,
        // so it is disposed first and the next box materializes from a tip that already carries the
        // approval (FR17, design D12 of harden-task-branch-contract).
        return CheckpointApproval.approve(
                support.taskRepository(),
                branch.taskId(),
                gated,
                order.run().definition(),
                support::disposeExistingEnvironment);
    }

    @Override
    public void resumeFrom(TakeOrder order, ContainerResumeBootstrap branch, TaskState reset) {
        var support = branch.support();
        // Disposed first for the reason approveCheckpoint states: no factory-side commit lands
        // behind a surviving box's back.
        support.disposeExistingEnvironment();
        support.taskRepository().resumeFrom(branch.taskId(), reset);
    }

    @Override
    public ParkDeliveryVerdict recordPark(TakeOrder order, ContainerResumeBootstrap branch, TaskOutcome.Paused paused) {
        // The same intent a fresh container park records (TakeContainerEngineExecution), factory-side
        // over bare objects. No delivery fence exists in container mode — the recording push is the
        // repository decorator's, best-effort as every container-mode push is — so the verdict is
        // Delivered.
        branch.support().recordPark(paused, TrackerWrite.OWED);
        return new ParkDeliveryVerdict.Delivered();
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
