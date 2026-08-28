package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import java.nio.file.Path;
import org.slf4j.MDC;

/**
 * Locates, materializes, and loads the resumed task's bundle for {@code take --resume} (design
 * D3, FR9): the same branch-locate/narrow-fetch/worktree-materialize/divergence-reconcile steps
 * {@link GitResumeRunner#bootstrap} performs for manual-run {@code --resume}, reused rather than
 * reimplemented. Extracted from {@link TakeResumeRunner} purely to keep both files within the
 * project's file-size guidance (`.claude/rules/process-invariants.md`).
 *
 * <p>Implements FR9 of add-tracker-port; FR6 of harden-task-branch-contract.
 *
 * @param git the task-git capability set: clone hardening, branch lookup, worktree
 *     materialization and divergence reconciliation; never null
 * @param worktreesRoot the root directory under which {@code <project-name>/<taskId>/}
 *     worktrees are created (design D6); never null
 * @param taskIdMdcKey the MDC key set to the branch's recorded taskId once bootstrap succeeds,
 *     matching {@link GitResumeRunner}'s own key
 */
record TakeResumeBootstrap(TaskGit git, Path worktreesRoot, String taskIdMdcKey) {

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
     */
    ResumeBootstrap bootstrap(Path cloneDir, String taskId) {
        // Runner-start hygiene for both resume flows: neutralize hooks on the clone before the
        // worktree materializes, so the shared .git/config carries core.hooksPath from the start
        // (FR17, design D11) — the config-write twin of the fresh path's pruneWorktrees hardening.
        git.branches().harden(cloneDir);
        BranchLocation location = git.branches().locate(cloneDir, taskId);
        // "Origin could not be asked" is not "no such branch" (FR6 of harden-task-branch-contract):
        // reporting it as a usage error would tell the operator to check their taskId when the
        // network is what failed.
        if (location instanceof BranchLocation.Unavailable(String reason)) {
            throw new BranchLocationUnavailableException(taskId, reason);
        }
        if (location instanceof BranchLocation.NotFound) {
            throw UsageException.branchNotFound(taskId);
        }

        String branchName = TaskIdSanitizer.branchName(taskId);
        Path worktree = git.worktrees().ensureWorktree(cloneDir, worktreesRoot, taskId, branchName);
        git.worktrees().reconcile(worktree, taskId, branchName);
        // Resume-start touchpoint (FR3 of fix-lifecycle-push): the divergence check above pulls
        // local up to what origin holds; this pushes origin up to what local holds, delivering a
        // commit an earlier instance recorded but never got pushed. Best-effort, never blocking.
        git.branches().reconcileRemote(cloneDir, taskId, "resume-start");

        TaskRecord content = git.store().readTaskRecord(worktree);
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
