package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Host-mode {@link ResumeMechanics}: a materialized worktree under {@code worktreesRoot}, its
 * {@code state.json} read from that worktree, and salvage through the worktree salvager — the
 * mechanics {@link TakeResumeRunner} already implements, adapted to the shared seam (design D8 of
 * add-serve-sandbox-lifecycle).
 *
 * <p>Implements FR1 of add-serve-sandbox-lifecycle; FR9, FR12, D3 of add-tracker-port.
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
        try {
            return resumeRunner.bootstrap(cloneDir, taskId);
        } catch (UncheckedIOException e) {
            // The branch's own cleanup commit (GitTaskRepository#recordOutcome on Completed, FR15)
            // removed .gnomish-task/ from the tip entirely — task.json AND state.json, in the same
            // commit — so the worktree read finds nothing. That is the delivered-but-unfinished
            // shape, not a fault; any other I/O fault stays a fault and propagates.
            if (e.getCause() instanceof NoSuchFileException) {
                return null;
            }
            throw e;
        }
    }

    @Override
    public TaskState readFinalState(ResumeBootstrap branch) {
        return git.store().readRecordedState(branch.worktreePath());
    }

    @Override
    public void confirmTerminalWrite(Path cloneDir, ResumeBootstrap branch) {
        git.store().taskRepository(cloneDir, worktreesRoot).confirmTerminalWrite(branch.taskId());
    }

    @Override
    public TakeResult resumeWithoutDecision(
            Path cloneDir,
            ResumeBootstrap branch,
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
            ResumeBootstrap branch,
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
