package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.agent.RoundClose;
import com.github.oinsio.gnomish.app.port.git.PendingVerification;
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit;
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The one owner of "no salvage over an interrupted verification" (design D22 of
 * migrate-verify-to-executor-contract): what happens to an interrupted round's uncommitted leftovers
 * before a resume continues and when a revocation stops the task. A register naming a snapshot or a
 * pending commit ({@link PendingVerification}) means the gnome's work is in the snapshot and the
 * leftovers are what the killed verification wrote, so nothing is salvaged over it: the host
 * discards them, with or without {@code --discard-work}, and the container keeps them in the box,
 * out of every commit — over a pending round it resets the box instead, since that round's terminal
 * snapshot is still to come and would stage them. Any other register is a round killed before its snapshot, whose leftovers
 * are salvaged as before (or discarded under {@code --discard-work}).
 *
 * <p>A salvage over such a register would commit the killed checks' residue: the register would
 * leave the snapshot, the recogniser would find nothing, and the next pickup would re-run the
 * stage executor and start again over a persisted key — the duty of FR19 and FR20 lost without a
 * word.
 *
 * <p>Callers, one per path that salvaged unconditionally before (the design's single-owner row "The
 * interrupted-verification resume"): {@link ContainerResumePreparation#prepare}, {@link
 * GitResumeContinuation#resumeFromRecordedPosition}, {@link TakeResumeRunner#resumeWithoutDecision},
 * and the two revocation stops, {@link com.github.oinsio.gnomish.app.take.RevocationHandler#handle}
 * and the container support's {@code revocationSalvageAndPush}. Both media read the register through
 * their one recogniser ({@code SnapshotTipCheck}) and hand the answer on, so the resume continues
 * from the record this owner judged by, never from a second read (crash-consistency item 14).
 *
 * <p>Implements FR19 of migrate-verify-to-executor-contract.
 */
public final class NoSalvageOverVerification {

    private NoSalvageOverVerification() {}

    /**
     * The rule over a host worktree: the leftovers are discarded over an interrupted verification
     * or under {@code --discard-work}, and salvage-committed otherwise.
     *
     * @param worktrees the worktree capabilities the register is read and the leftovers handled
     *     through; never null
     * @param worktree the task's worktree; never null
     * @param taskId the task being resumed or stopped; never blank
     * @param discardWork {@code true} for {@code --discard-work}
     * @return the interrupted verification the register names, or {@code null} when it names none
     */
    public static @Nullable PendingVerification onHost(
            TaskWorktreeGit worktrees, Path worktree, String taskId, boolean discardWork) {
        Optional<PendingVerification> registered = worktrees.interruptedVerification(worktree, taskId);
        var leftovers = worktrees.salvage(worktree);
        if (registered.isPresent() || discardWork) {
            leftovers.discard();
        } else {
            leftovers.salvage(taskId);
        }
        return registered.orElse(null);
    }

    /**
     * The rule in the task's box: the leftovers are salvaged in-box only when the register names
     * no interrupted verification; otherwise they stay in the box, out of every commit — except over
     * a pending round ({@link RoundClose#PENDING}), whose terminal snapshot would stage them: there the
     * box is reset to its {@code HEAD}, so a resumed poll runs at the registered commit as the host's
     * discarded worktree does (design D11 of extract-agent-round-runner, "Resume").
     *
     * @param support the sandboxed run support of the task, its box live; never null
     * @param registered what the support's recogniser read at the register before the box was made
     *     live; never null
     * @param taskId the task being resumed or stopped; never blank
     * @return the interrupted verification the register names, or {@code null} when it names none
     */
    public static @Nullable PendingVerification inBox(
            SandboxRunSupport support, Optional<PendingVerification> registered, String taskId) {
        if (registered.isEmpty()) {
            support.salvageLeftovers(taskId);
        } else if (registered.get().close() == RoundClose.PENDING) {
            support.discardLeftovers();
        }
        return registered.orElse(null);
    }

    /**
     * {@link #inBox} for a revocation stop over the box as the claim loss found it, which needs
     * only whether the leftovers were kept out.
     *
     * @return {@code true} when the register names an interrupted verification, so nothing was
     *     salvaged
     */
    public static boolean keptOutInBox(SandboxRunSupport support, String taskId) {
        return inBox(support, support.pendingVerification(), taskId) != null;
    }
}
