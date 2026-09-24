package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Host-mode {@link ResumeMechanics}: a materialized worktree under {@code worktreesRoot}, its
 * {@code state.json} read at that worktree's {@code HEAD} (design D1 of fix-envelope-medium), and
 * salvage through the worktree salvager — the
 * mechanics {@link TakeResumeRunner} already implements, adapted to the shared seam (design D8 of
 * add-serve-sandbox-lifecycle).
 *
 * <p>Implements FR1 of add-serve-sandbox-lifecycle; FR9, FR12, D3 of add-tracker-port; FR3 of
 * harden-task-branch-contract.
 *
 * @param resumeRunner the worktree-backed resume machinery; never null
 * @param git the task-git capability set the store reads and marker write go through; never null
 * @param worktreesRoot the root the task's worktree and lifecycle repository are rooted under
 * @param definition the pipeline this resume advances through; never null
 */
record HostResumeMechanics(
        TakeResumeRunner resumeRunner, TaskGit git, Path worktreesRoot, PipelineDefinition definition)
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
        git.store().taskRepository(cloneDir, worktreesRoot).confirmTerminalWrite(branch.taskId());
    }

    @Override
    public void finishCleanup(Path cloneDir, ResumeBootstrap branch) {
        var taskRepository = git.store().taskRepository(cloneDir, worktreesRoot);
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
    public TaskContext appendDecision(
            Path cloneDir, ResumeBootstrap branch, TaskState finalState, TaskState resetState, String decisionText) {
        return resumeRunner.appendDecision(cloneDir, branch, finalState, resetState, decisionText);
    }

    @Override
    public TakeResult resumeDecided(
            TakeOrder order, ResumeBootstrap branch, TaskContext context, TaskState resetState) {
        return resumeRunner.resumeDecided(order, branch, context, resetState);
    }
}
