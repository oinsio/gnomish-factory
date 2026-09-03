package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The fence a host-mode park's terminal tracker write is preceded by (FR4 of fix-lifecycle-push):
 * before the tracker announces a park to every instance, the branch tip carrying that park — the
 * recorded outcome and its {@code pendingTrackerWrite} marker — must be on {@code origin}, or the
 * human must be told it is not. Without it the tracker signals a park whose commit origin lacks,
 * and a reconcile-on-resume from another machine reads stale state.
 *
 * <p>A thin sibling of {@link RemoteAttemptDelivery} over the same shared core (design D4), not a
 * second implementation: the same verify → push → one bounded re-attempt sequence, differing only
 * in what is checked (the branch tip rather than a recorded attempt commit) and how failure maps
 * (a line in the park report rather than a CannotVerify check result). With no {@code origin} the
 * fence is a silent no-op — a purely local run has no remote for the park to be missing from.
 *
 * <p>Exhaustion never blocks the park (FR5): the verdict is data the caller carries into its
 * tracker write, which proceeds either way.
 *
 * <p>A push that did not run to its own exit is not a failed push (design D7 of
 * bound-subprocess-commands): it burns no re-attempt — a second bounded wait on a remote that
 * already proved unresponsive is the hang this fence must not reintroduce — and it never yields
 * the {@code origin is behind} note on its own authority. A timed-out push is an <em>unknown</em>
 * remote outcome, because the kill may have landed after the transfer did, so the fence asks
 * {@code origin} once more and asserts "behind" only when {@code origin} itself answers that the
 * tip is missing. An interrupted one is not re-checked at all: the run is shutting down.
 *
 * <p>Implements FR4, FR5, NFR-R2, NFR-O1, UX2 of fix-lifecycle-push; FR7, FR8, NFR-O2, UX2, UX3
 * of bound-subprocess-commands.
 */
public final class ParkDeliveryFence {

    private static final Logger log = LoggerFactory.getLogger(ParkDeliveryFence.class);

    private final OriginRemote origin;
    private final LocalBranchTip localTip;
    private final RemoteBranchTip remoteTip;
    private final RefspecPush push;

    /**
     * @param runner the git subprocess seam; never null
     */
    public ParkDeliveryFence(GitProcessRunner runner) {
        this.origin = new OriginRemote(runner);
        this.localTip = new LocalBranchTip(runner);
        this.remoteTip = new RemoteBranchTip(runner);
        this.push = new RefspecPush(runner);
    }

    /**
     * Verifies — and, where needed, delivers — {@code taskId}'s branch tip to {@code origin}.
     *
     * @param cloneDir the clone the branch lives in and the push runs from; never null
     * @param taskId the parking task's tracker id; never blank
     * @return {@link ParkDeliveryVerdict.Delivered} when origin carries the tip (or there is no
     *     origin, or no branch), {@link ParkDeliveryVerdict.Undelivered} with the report note when
     *     both attempts failed
     */
    public ParkDeliveryVerdict ensureDelivered(Path cloneDir, String taskId) {
        if (!origin.isConfigured(cloneDir)) {
            return new ParkDeliveryVerdict.Delivered();
        }
        String branch = TaskIdSanitizer.branchName(taskId);
        Optional<String> tip = localTip.read(cloneDir, branch);
        if (tip.isEmpty()) {
            // A park always records its outcome on this branch first, so by the time the fence runs
            // the branch is there — an unreadable tip means the ref read itself failed (a branch
            // deleted from under the run, a damaged clone). Nothing can be verified or pushed, so
            // the park proceeds (FR5), but the operator gets the one line that says the delivery
            // check never actually ran rather than a silent pass (NFR-O1).
            log.warn(
                    OperatorEvent.PARK_FENCE_TIP_UNREADABLE.head()
                            + "park delivery fence skipped, local branch tip unreadable: taskId={}, branch={}, cloneDir={}",
                    taskId,
                    branch,
                    cloneDir);
            return new ParkDeliveryVerdict.Delivered();
        }
        if (remoteTip.carries(cloneDir, branch, tip.get())) {
            return new ParkDeliveryVerdict.Delivered();
        }

        GitCommandResult result = push.push(cloneDir, branch);
        if (result.termination() == Termination.EXITED && result.exitCode() != 0) {
            // FR12: the first of two bounded attempts failing is not something an operator acts
            //     on — only an exhausted fence is. The line stays, at INFO, so a post-mortem can
            //     still see the retry happened.
            log.info(
                    "park delivery push failed, re-attempting once: taskId={}, branch={}, stderr={}",
                    taskId,
                    branch,
                    LogText.forLog(result.stderr()));
            result = push.push(cloneDir, branch);
        }
        if (result.termination() != Termination.EXITED) {
            return unverified(cloneDir, taskId, branch, tip.get(), result);
        }
        if (result.exitCode() != 0) {
            log.warn(
                    OperatorEvent.PARK_FENCE_EXHAUSTED.head()
                            + "park delivery fence exhausted, parking anyway: taskId={}, branch={}, stderr={}",
                    taskId,
                    branch,
                    LogText.forLog(result.stderr()));
            return new ParkDeliveryVerdict.Undelivered(ParkDeliveryNotes.behind(branch));
        }
        return new ParkDeliveryVerdict.Delivered();
    }

    /**
     * The verdict for a push that never established a remote outcome (FR7). An interrupt ends the
     * fence where it stands — the run is going away, and the one remaining remote read would only
     * be another command to abandon. A timeout gets exactly one bounded re-check, and only
     * {@code origin}'s own answer can turn it into the {@code origin is behind} claim.
     */
    private ParkDeliveryVerdict unverified(
            Path cloneDir, String taskId, String branch, String tip, GitCommandResult result) {
        if (result.termination() == Termination.INTERRUPTED) {
            log.warn(
                    OperatorEvent.PARK_FENCE_INTERRUPTED.head()
                            + "park delivery push interrupted, delivery unverified, parking anyway: taskId={}, branch={}",
                    taskId,
                    branch);
            return new ParkDeliveryVerdict.Undelivered(
                    ParkDeliveryNotes.unverified(branch, "was interrupted before it finished"));
        }
        RemoteBranchTip.Carriage carriage = remoteTip.confirm(cloneDir, branch, tip);
        if (carriage == RemoteBranchTip.Carriage.CARRIES) {
            // FR12: a recovered transient. The push did not report success, but origin has the
            //     park — nothing is degraded and there is nothing to act on.
            log.info("park delivery push timed out, but origin carries the park: taskId={}, branch={}", taskId, branch);
            return new ParkDeliveryVerdict.Delivered();
        }
        if (carriage == RemoteBranchTip.Carriage.ABSENT) {
            log.warn(
                    OperatorEvent.PARK_FENCE_TIMED_OUT_ORIGIN_BEHIND.head()
                            + "park delivery push timed out, origin confirmed behind, parking anyway: taskId={}, branch={}",
                    taskId,
                    branch);
            return new ParkDeliveryVerdict.Undelivered(ParkDeliveryNotes.behind(branch));
        }
        log.warn(
                OperatorEvent.PARK_FENCE_TIMED_OUT_ORIGIN_SILENT.head()
                        + "park delivery push timed out and origin did not answer the re-check, parking anyway:"
                        + " taskId={}, branch={}",
                taskId,
                branch);
        return new ParkDeliveryVerdict.Undelivered(ParkDeliveryNotes.unverified(
                branch, "was cut off on its deadline and origin did not answer the re-check"));
    }
}
