package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.TrackerWrite;
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Host-mode {@link ResumeMechanics}: a materialized worktree in the registered clone's own
 * worktree folder (FR9 of add-project-registry), its
 * {@code state.json} read at that worktree's {@code HEAD} (design D1 of fix-envelope-medium), and
 * salvage through the worktree salvager — the
 * mechanics {@link TakeResumeRunner} already implements, adapted to the shared seam (design D8 of
 * add-serve-sandbox-lifecycle).
 *
 * <p>Implements FR1 of add-serve-sandbox-lifecycle; FR9, FR12, D3 of add-tracker-port; FR3 of
 * harden-task-branch-contract; FR9 of add-project-registry; FR3, FR4, FR7 of make-checkpoint-gate-durable
 * (the approval, the resumed write and a gate's owed park go through the worktree-rooted lifecycle
 * repository).
 *
 * @param resumeRunner the worktree-backed resume machinery; never null
 * @param git the task-git capability set the store reads and marker write go through; never null
 * @param registeredClone the registered clone the task's lifecycle repository is rooted at; never null
 * @param definition the pipeline this resume advances through; never null
 */
record HostResumeMechanics(
        TakeResumeRunner resumeRunner, TaskGit git, RegisteredClone registeredClone, PipelineDefinition definition)
        implements ResumeMechanics<ResumeBootstrap> {

    @Override
    public @Nullable ResumeBootstrap loadBranch(Path cloneDir, String taskId) {
        // An empty read is the delivered-but-unfinished shape, not a fault: the branch's own
        // cleanup commit (GitTaskRepository#recordOutcome on Completed, FR15) removed
        // .gnomish-task/ from the tip entirely — the task record AND the state, in the same commit
        // — so the tip carries no task envelope. Absence travels as a value now, so no cause chain
        // has to be inspected to tell it apart from a fault (design D2 of fix-envelope-medium).
        return resumeRunner.bootstrap(cloneDir, taskId);
    }

    @Override
    public TaskState readFinalState(ResumeBootstrap branch) {
        // A pre-contract tip (FR3, design D2 of harden-task-branch-contract): the task record is
        // present — loadBranch above read it — but the state is not, because the branch was
        // created before the STARTED commit carried the initial state. That is a legal shape, not
        // a fault: the task resumes its first stage from scratch, exactly as a branch created
        // today would. The container twin is ContainerTipReader#readStateOrInitial.
        return git.store()
                .readRecordedState(branch.worktreePath())
                .orElseGet(() ->
                        TaskState.atStageStart(definition.stages().getFirst().name()));
    }

    @Override
    public void confirmTerminalWrite(Path cloneDir, ResumeBootstrap branch) {
        git.store().taskRepository(registeredClone).confirmTerminalWrite(branch.taskId());
    }

    @Override
    public void finishCleanup(Path cloneDir, ResumeBootstrap branch) {
        var taskRepository = git.store().taskRepository(registeredClone);
        // Read before the cleanup commit: it removes .gnomish-task/ from the tip, and a read made
        // after it — the reads resolve at HEAD — finds nothing, which the pre-contract fallback
        // in readFinalState would
        // dress up as a fabricated first-stage state for a task that is already delivered.
        var outcome = new TaskOutcome.Completed(readFinalState(branch));
        taskRepository.finishCleanup(branch.taskId());
        git.worktrees().cleanUp(cloneDir, branch.worktreePath(), outcome);
    }

    @Override
    public TakeResult resumeWithoutDecision(TakeOrder order, ResumeBootstrap branch, TaskState finalState) {
        return resumeRunner.resumeWithoutDecision(order, branch, finalState);
    }

    @Override
    public TaskState approveCheckpoint(TakeOrder order, ResumeBootstrap branch) {
        return CheckpointApproval.approve(
                git.store().taskRepository(registeredClone),
                branch.taskId(),
                readFinalState(branch),
                order.run().definition(),
                () -> {});
    }

    @Override
    public void resumeFrom(TakeOrder order, ResumeBootstrap branch, TaskState reset) {
        git.store().taskRepository(registeredClone).resumeFrom(branch.taskId(), reset);
    }

    @Override
    public ParkDeliveryVerdict recordPark(TakeOrder order, ResumeBootstrap branch, TaskOutcome.Paused paused) {
        // The same intent and fence a fresh park runs (TakeEngineExecution): the outcome commit
        // with the pending marker, then the delivery fence before the tracker announces the park.
        Path cloneDir = order.run().cloneDir();
        GitOutcomeRecorder.recordIntent(
                git, git.store().taskRepository(registeredClone), cloneDir, branch.taskId(), paused, TrackerWrite.OWED);
        return git.branches().fenceParkDelivery(cloneDir, branch.taskId());
    }

    @Override
    public TaskContext appendDecision(
            Path cloneDir, ResumeBootstrap branch, TaskState finalState, TaskState resetState, String decisionText) {
        return resumeRunner.appendDecision(branch, finalState, resetState, decisionText);
    }

    @Override
    public TakeResult resumeDecided(
            TakeOrder order, ResumeBootstrap branch, TaskContext context, TaskState resetState) {
        return resumeRunner.resumeDecided(order, branch, context, resetState);
    }
}
