package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.gitobjects.MissingObjectException;
import com.github.oinsio.gnomish.sandbox.Segment;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Locates and loads the resumed task's bundle for container-mode {@code take} (FR1 of
 * add-serve-sandbox-lifecycle): mirrors {@link TakeResumeBootstrap}'s branch-locate/load steps,
 * but over bare git objects — {@link com.github.oinsio.gnomish.app.port.git.TaskBranchGit
 * #ensureLocalTaskBranch} in place of a worktree materialize (the ref-only path container mode
 * already takes for {@code run --resume}, see {@link ContainerResumeRunner}) — and builds the
 * {@link com.github.oinsio.gnomish.app.port.run.SandboxRunSupport} bundle every subsequent
 * container-mode resume step reads and writes through.
 *
 * <p>Implements FR1 of add-serve-sandbox-lifecycle; FR9 of add-tracker-port.
 *
 * @param git the task-git capability set: clone hardening and branch reconciliation; never null
 * @param containerTakeSupport the container-dispatch collaborators; never null
 * @param taskIdMdcKey the MDC key set to the branch's recorded taskId once bootstrap succeeds
 */
record TakeContainerResumeBootstrap(TaskGit git, ContainerTakeSupport containerTakeSupport, String taskIdMdcKey) {

    private static final Logger log = LoggerFactory.getLogger(TakeContainerResumeBootstrap.class);

    /**
     * @param cloneDir the project clone; never mutated
     * @param taskId the tracker's original taskId
     * @param segments the run's container-bound segment plan; never empty
     * @param definition the loaded pipeline the run advances through; never null
     * @return the loaded bundle, or {@code null} when the reconciled branch tip carries no {@code
     *     .gnomish-task/} at all — the delivered-and-cleaned shape {@link
     *     ResumeMechanics#loadBranch} reports for reconcile (design D8 of add-serve-sandbox-lifecycle)
     * @throws UsageException if no branch for {@code taskId} is found
     */
    @Nullable
    ContainerResumeBootstrap bootstrap(
            Path cloneDir, String taskId, List<Segment> segments, PipelineDefinition definition) {
        git.branches().harden(cloneDir);
        if (!git.branches().ensureLocalTaskBranch(cloneDir, taskId)) {
            throw UsageException.branchNotFound(taskId);
        }

        var support = containerTakeSupport
                .containerSupportFactory()
                .create(
                        cloneDir,
                        taskId,
                        segments,
                        containerTakeSupport.sandboxProperties(),
                        containerTakeSupport.factoryProperties(),
                        definition,
                        List.of());
        TaskRecord content;
        try {
            content = support.readTaskJson();
        } catch (MissingObjectException absent) {
            // The branch's own cleanup commit (FR15 of add-git-workflow) removed .gnomish-task/ from
            // the tip on Completed, so the bare-object read finds no blob there. That is the
            // delivered-but-unfinished shape — the work landed, the tracker finish did not — which
            // TakeDispositionResume reconciles as a deferred finish, not a fault. The read is taken
            // after ensureLocalTaskBranch above, so it sees the reconciled tip, never a stale local one.
            log.debug("no task.json at the tip of the branch for {}: delivered and cleaned up", taskId, absent);
            return null;
        }
        MDC.put(taskIdMdcKey, content.context().taskId());
        return new ContainerResumeBootstrap(
                taskId,
                content.context(),
                content.outcome(),
                content.lastEscalation(),
                support,
                TaskIdSanitizer.branchName(taskId),
                content.trackerWritePending());
    }
}
