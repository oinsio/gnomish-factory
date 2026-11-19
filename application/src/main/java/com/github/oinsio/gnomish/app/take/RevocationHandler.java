package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.NoSalvageOverVerification;
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit;
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;

/**
 * Runs the revocation salvage protocol once {@link RevocationDetectedException} has propagated
 * out of {@code engine.run(...)} (design D2, FR15): the round that was executing when revocation
 * was detected is already durably committed (the check in {@link
 * RevocationCheckingAttemptPersistence} always runs after the delegate's persist), so this handler
 * only has to deal with whatever the interrupted next round left uncommitted, then hand the task
 * back cleanly.
 *
 * <p>The protocol, in order: settle the uncommitted leftovers through {@link
 * NoSalvageOverVerification} — salvage-committed in the worktree, or discarded when the register
 * names a snapshot or a pending commit, whose verification the revocation interrupted (design D22
 * of migrate-verify-to-executor-contract) — letting a {@code GitSalvageFailedException} propagate,
 * since a failed salvage is a genuine local-durability problem, not something this handler can
 * paper over; best-effort closing push of the branch and its factory register ({@link
 * TaskBranchGit#pushClosingBestEffort}, which never throws); post a
 * structural "work stopped" note ({@link RevocationStopNote}, naming the salvage or the discard);
 * release the claim. The tracker's logical state is deliberately
 * left untouched (FR15) — revocation may have been caused by a human closing the task or claiming
 * it directly, and this handler must not fight that action, only stop working and get out of the
 * way. None of {@code park}, {@code recordAbort}, or {@code finish} are ever called here: this is
 * not an abort, an escalation, or a delivery.
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.app.TakeContainerEngineExecution} (package-
 * private, not linkable from this package): both
 * must end a revoked claim with the same two tracker writes — {@code postNote} of the one {@link
 * RevocationStopNote} followed by {@code release}, and never {@code park}, {@code recordAbort} or
 * {@code finish} (FR15) — and both settle the leftovers through the one {@link
 * NoSalvageOverVerification}. Only the salvage mechanics differ by medium: this end acts on a
 * worktree path, the sandboxed end calls {@code SandboxRunSupport.revocationSalvageAndPush}. An exit
 * that changed on one medium alone would make the two modes report the same event differently.
 *
 * <p>Implements FR15, D2 of add-tracker-port; FR19 of migrate-verify-to-executor-contract.
 *
 * @param tracker the tracker port used for the best-effort {@code postNote} and the {@code
 *     release} that drops this instance's claim; never null
 * @param worktrees the worktree capabilities the register is read and the interrupted round's
 *     uncommitted leftovers salvaged or discarded through; never null
 * @param branchPush best-effort pushes the task branch after the salvage commit; never null
 */
public record RevocationHandler(Tracker tracker, TaskWorktreeGit worktrees, TaskBranchGit branchPush) {

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
     * @param reason free-text description of why the task was revoked, folded into the posted stop
     *     note; never blank
     * @return {@link TakeResult.Revoked} carrying {@code finalState} and the posted stop note
     * @throws com.github.oinsio.gnomish.app.port.git.GitSalvageFailedException if the salvage
     *     commit itself fails — a genuine local-durability problem, propagated rather than
     *     swallowed
     * @throws com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException if a tip read
     *     the salvage depends on did not run to its own exit, so it established nothing — also
     *     propagated, for the same reason
     */
    public TakeResult.Revoked handle(
            TaskRef ref, String taskId, TaskState finalState, Path worktreeRoot, String reason) {
        boolean kept = NoSalvageOverVerification.onHost(worktrees, worktreeRoot, taskId, false) != null;
        branchPush.pushClosingBestEffort(worktreeRoot, taskId);

        String note = RevocationStopNote.of(reason, kept);
        tracker.postNote(ref, note);
        tracker.release(ref);

        return new TakeResult.Revoked(finalState, UntrustedText.factory(note));
    }
}
