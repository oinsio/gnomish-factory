package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import java.nio.file.Path;

/**
 * The host mode's entry into the touchpoint reconciliation (FR3 of fix-lifecycle-push): resolves
 * the task branch's local tip with the worktree-side reader — the branch ref in the factory clone,
 * which is what a host run's commits advance — and hands it to the shared {@link
 * OriginReconciliation}. Container mode does not go through here: it resolves its own tip through
 * {@code GitObjects.resolveRef} and calls the same check directly (design D3).
 *
 * <p>A branch that does not exist locally has no tip to deliver, so the check is skipped entirely.
 * Like everything on this path, it never throws.
 *
 * <p>Implements FR3 of fix-lifecycle-push.
 */
final class TaskBranchReconciliation {

    private final LocalBranchTip localTip;
    private final OriginReconciliation reconciliation;

    TaskBranchReconciliation(GitProcessRunner runner) {
        this.localTip = new LocalBranchTip(runner);
        this.reconciliation = new OriginReconciliation(runner);
    }

    /**
     * Runs the reconciliation for {@code taskId}'s branch in {@code cloneDir}.
     *
     * @param cloneDir the clone the branch lives in; never null
     * @param taskId the tracker's original taskId; never blank
     * @param touchpoint what triggered the check, for log context; never blank
     */
    void reconcile(Path cloneDir, String taskId, String touchpoint) {
        String branch = TaskIdSanitizer.branchName(taskId);
        localTip.read(cloneDir, branch)
                .ifPresent(tip -> reconciliation.reconcile(taskId, touchpoint, cloneDir, branch, tip));
    }
}
