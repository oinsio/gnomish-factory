package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.BranchAdoption;
import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.BranchLocationRefusedException;
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException;
import com.github.oinsio.gnomish.app.port.git.LocalTaskBranch;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.MDC;

/**
 * Locates, materializes, and loads the resumed task's bundle for worktree-mode resume (design D3,
 * FR9): the single owner of the branch-locate/narrow-fetch/worktree-materialize/
 * divergence-reconcile sequence. Both resume entry points delegate here rather than reimplementing
 * it — {@link TakeResumeRunner#bootstrap} for tracker-driven {@code take --resume} and {@link
 * GitResumeRunner#bootstrap} for manual-run {@code run --resume} — so the two flows cannot drift
 * apart in what a resumed worktree is brought to before {@code task.json} is read at its
 * {@code HEAD}.
 *
 * <p>Kept in sync with {@link TakeContainerResumeBootstrap}: both must require the clone's ref
 * backend ({@code requireRefFormat}), reconcile the remote on resume-start, and build the same
 * resume bundle shape (context, outcome, last escalation, base commit and the durable base pin)
 * from the loaded task record.
 *
 * <p>Implements FR9 of add-tracker-port; FR6 of harden-task-branch-contract; FR9 of
 * add-project-registry; FR8 of fix-terminal-receipt-convergence.
 *
 * @param git the task-git capability set: the clone's ref-backend check, branch lookup, worktree
 *     materialization and divergence reconciliation; never null
 * @param registeredClone the registered clone whose own worktree folder the resumed worktree is
 *     materialized in (design D6; FR9 of add-project-registry); never null
 * @param taskIdMdcKey the MDC key set to the branch's recorded taskId once bootstrap succeeds,
 *     matching {@link GitResumeRunner}'s own key
 */
record TakeResumeBootstrap(TaskGit git, RegisteredClone registeredClone, String taskIdMdcKey) {

    /**
     * Locates the task branch for {@code taskId} in {@code cloneDir}, adopts it when the clone
     * holds it only as a remote-tracking ref, materializes its worktree over the local branch,
     * reconciles local/origin divergence, and loads the {@code task.json} its {@code HEAD}
     * carries.
     *
     * <p>Implements FR9 of add-tracker-port.
     *
     * @param cloneDir the project clone; never mutated
     * @param taskId the tracker's original taskId, as supplied to {@code take --resume}
     * @return the bootstrap bundle: located branch, materialized worktree, loaded task record —
     *     or empty when the branch tip carries no task envelope, which is the delivered shape a
     *     cleanup commit leaves behind (design D1, D2 of fix-envelope-medium)
     * @throws UsageException if no branch for {@code taskId} is found
     * @throws BranchLocationUnavailableException if origin could not be asked whether the branch
     *     exists — a network failure is not a missing branch, so it is reported apart from the
     *     usage error (FR6 of harden-task-branch-contract)
     * @throws BranchLocationRefusedException if origin served the branch and git's object
     *     validation refused it — a fact about the branch's history, reported apart from both
     *     (FR5 of own-git-transfer-argv)
     * @throws com.github.oinsio.gnomish.app.port.git.DivergedBranchException if local and origin
     *     have truly diverged while no claim is held on the task: the automatic discard is the
     *     claim protocol's arbitration, so the claimless {@code run --resume} caller stops and
     *     reports instead (FR8 of harden-task-branch-contract)
     */
    Optional<ResumeBootstrap> bootstrap(Path cloneDir, String taskId) {
        // The ref-backend check first, ahead of the first write: a refused clone gets no branch,
        // ref or worktree-registry write (design D4 of own-git-invocation-policy).
        git.branches().requireRefFormat(cloneDir);
        // Adopt over the location the lookup just refreshed, before the worktree (design D9, D11
        // of fix-terminal-receipt-convergence): a branch this clone holds only as a remote-tracking
        // ref becomes local through the one owner, so the worktree below names an existing branch
        // and git resolves nothing. The owner keeps the lookup's three refusals apart (FR6 of
        // harden-task-branch-contract): "origin could not be asked" and "git's validation refused
        // the branch" throw as themselves, and only a confirmed absence is the usage error — a
        // network failure reported as a missing branch would send the operator to check the taskId.
        BranchLocation location = git.branches().locate(cloneDir, taskId);
        LocalTaskBranch branch =
                switch (git.branches().adopt(cloneDir, taskId, location)) {
                    case LocalTaskBranch local -> local;
                    case BranchAdoption.Nowhere() -> throw UsageException.branchNotFound(taskId);
                };

        String branchName = TaskIdSanitizer.branchName(taskId);
        Path worktree = git.worktrees().ensureWorktree(registeredClone, branch);
        git.worktrees().reconcile(worktree, taskId, branchName);
        // Resume-start touchpoint (FR3 of fix-lifecycle-push): the divergence check above pulls
        // local up to what origin holds; this pushes origin up to what local holds, delivering a
        // commit an earlier instance recorded but never got pushed. Best-effort, never blocking.
        git.branches().reconcileRemote(cloneDir, taskId, "resume-start");

        return git.store().readTaskRecord(worktree, taskId).map(content -> bundle(branch, worktree, content));
    }

    private ResumeBootstrap bundle(LocalTaskBranch branch, Path worktree, TaskRecord content) {
        MDC.put(taskIdMdcKey, content.context().taskId());
        return new ResumeBootstrap(
                branch.taskId(),
                content.context(),
                content.outcome(),
                content.lastEscalation(),
                worktree,
                branch,
                content.baseCommit(),
                content.trackerWritePending(),
                content.pin());
    }
}
