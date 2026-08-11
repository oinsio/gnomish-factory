package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.BranchLocation;
import com.github.oinsio.gnomish.adapter.git.FactoryCloneHardening;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.TaskBranchLocator;
import com.github.oinsio.gnomish.adapter.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.adapter.git.TaskWorktreeManager;
import com.github.oinsio.gnomish.adapter.git.WorktreeDivergenceCheck;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonContent;
import java.nio.file.Path;
import org.slf4j.MDC;

/**
 * Locates, materializes, and loads the resumed task's bundle for {@code take --resume} (design
 * D3, FR9): the same branch-locate/narrow-fetch/worktree-materialize/divergence-reconcile steps
 * {@link GitResumeRunner#bootstrap} performs for manual-run {@code --resume}, reused rather than
 * reimplemented. Extracted from {@link TakeResumeRunner} purely to keep both files within the
 * project's file-size guidance (`.claude/rules/process-invariants.md`).
 *
 * <p>Implements FR9 of add-tracker-port.
 *
 * @param worktreesRoot the root directory under which {@code <project-name>/<taskId>/}
 *     worktrees are created (design D6); never null
 * @param taskIdMdcKey the MDC key set to the branch's recorded taskId once bootstrap succeeds,
 *     matching {@link GitResumeRunner}'s own key
 */
record TakeResumeBootstrap(Path worktreesRoot, String taskIdMdcKey) {

    /**
     * Locates the task branch for {@code taskId} in {@code cloneDir}, materializes its worktree,
     * reconciles local/origin divergence, and loads its {@code task.json}.
     *
     * <p>Implements FR9 of add-tracker-port.
     *
     * @param cloneDir the project clone; never mutated
     * @param taskId the tracker's original taskId, as supplied to {@code take --resume}
     * @return the bootstrap bundle: located branch, materialized worktree, loaded task.json
     * @throws UsageException if no branch for {@code taskId} is found
     * @throws com.github.oinsio.gnomish.adapter.git.DivergedBranchException if local and origin
     *     history diverged (no bypass exists today)
     */
    ResumeBootstrap bootstrap(Path cloneDir, String taskId) {
        GitProcessRunner runner = new GitProcessRunner();
        // Runner-start hygiene for both resume flows: neutralize hooks on the clone before the
        // worktree materializes, so the shared .git/config carries core.hooksPath from the start
        // (FR17, design D11) — the config-write twin of the fresh path's pruneWorktrees hardening.
        new FactoryCloneHardening(runner).harden(cloneDir);
        BranchLocation location = new TaskBranchLocator(runner).locate(cloneDir, taskId);
        if (location instanceof BranchLocation.NotFound) {
            throw new UsageException("could not resume task \"" + taskId + "\": no branch \""
                    + TaskIdSanitizer.branchName(taskId)
                    + "\" found locally, as a remote-tracking ref, or on origin (even after a fetch attempt)");
        }

        String branchName = TaskIdSanitizer.branchName(taskId);
        Path worktree = new TaskWorktreeManager(runner, worktreesRoot).ensureWorktree(cloneDir, taskId, branchName);
        new WorktreeDivergenceCheck(runner, worktree).reconcile(taskId, branchName);

        TaskJsonContent content = GitFreshTaskSupport.readTaskJson(worktree);
        MDC.put(taskIdMdcKey, content.context().taskId());
        return new ResumeBootstrap(
                taskId,
                content.context(),
                content.outcome(),
                content.lastEscalation(),
                worktree,
                branchName,
                content.baseCommit(),
                content.trackerWritePending());
    }
}
