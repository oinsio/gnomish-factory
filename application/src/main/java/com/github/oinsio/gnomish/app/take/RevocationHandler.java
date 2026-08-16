package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.git.TaskBranchGit;
import com.github.oinsio.gnomish.app.port.git.TaskSalvage;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.nio.file.Path;

/**
 * Runs the revocation salvage protocol once {@link RevocationDetectedException} has propagated
 * out of {@code engine.run(...)} (design D2, FR15): the round that was executing when revocation
 * was detected is already durably committed (the check in {@link
 * RevocationCheckingAttemptPersistence} always runs after the delegate's persist), so this handler
 * only has to deal with whatever the interrupted next round left uncommitted, then hand the task
 * back cleanly.
 *
 * <p>The protocol, in order: salvage-commit any uncommitted leftovers ({@link
 * TaskSalvage#salvage} — the host realization commits in the worktree, the sandboxed one commits
 * in-box and harvests (FR6 of add-sandbox-core), letting a {@code GitSalvageFailedException}
 * propagate — a failed salvage is a genuine local-durability problem, not something this handler
 * can paper over); best-effort push the branch ({@link TaskBranchGit#pushBestEffort}, which never
 * throws); post a
 * structural "work stopped" note; release the claim. The tracker's logical state is deliberately
 * left untouched (FR15) — revocation may have been caused by a human closing the task or claiming
 * it directly, and this handler must not fight that action, only stop working and get out of the
 * way. None of {@code park}, {@code recordAbort}, or {@code finish} are ever called here: this is
 * not an abort, an escalation, or a delivery.
 *
 * <p>Implements FR15, D2 of add-tracker-port.
 *
 * @param tracker the tracker port used for the best-effort {@code postNote} and the {@code
 *     release} that drops this instance's claim; never null
 * @param worktreeSalvage salvages the interrupted round's uncommitted leftovers — in the task
 *     worktree (host) or inside the environment with a harvest (sandboxed); never null
 * @param branchPush best-effort pushes the task branch after the salvage commit; never null
 */
public record RevocationHandler(Tracker tracker, TaskSalvage worktreeSalvage, TaskBranchGit branchPush) {

    /**
     * Runs the full revocation protocol for one revoked task: salvage, best-effort push, stop
     * note, release. Never calls {@code park}, {@code recordAbort}, or {@code finish} — the
     * tracker's logical state is left exactly as revocation found it (FR15).
     *
     * @param ref the revoked task's identity; never null
     * @param taskId the tracker's original taskId, used both for the salvage commit's context and
     *     to derive the task branch name to push; never blank
     * @param finalState the last known task state at the point of revocation; never null
     * @param worktreeRoot the checked-out task worktree; salvage and push run with this path as
     *     the git {@code cwd}
     * @param branch the task branch name to push, e.g. {@link
     *     com.github.oinsio.gnomish.app.git.TaskIdSanitizer#branchName}; never blank
     * @param reason free-text description of why the task was revoked, folded into the posted stop
     *     note; never blank
     * @return {@link TakeResult.Revoked} carrying {@code finalState} and the posted stop note
     * @throws com.github.oinsio.gnomish.app.port.git.GitSalvageFailedException if the salvage
     *     commit itself fails — a genuine local-durability problem, propagated rather than
     *     swallowed
     */
    public TakeResult.Revoked handle(
            TaskRef ref, String taskId, TaskState finalState, Path worktreeRoot, String branch, String reason) {
        worktreeSalvage.salvage(taskId);
        branchPush.pushBestEffort(worktreeRoot, branch);

        String note = "Work stopped: " + reason + ". Uncommitted work was salvage-committed and the branch"
                + " left in place for whoever resumes this task.";
        tracker.postNote(ref, note);
        tracker.release(ref);

        return new TakeResult.Revoked(finalState, note);
    }
}
