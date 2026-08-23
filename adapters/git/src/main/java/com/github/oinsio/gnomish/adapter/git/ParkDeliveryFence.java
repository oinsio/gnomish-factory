package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict;
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
 * <p>Implements FR4, FR5, NFR-R2, NFR-O1, UX2 of fix-lifecycle-push.
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
                    "park delivery fence skipped, local branch tip unreadable: taskId={}, branch={}, cloneDir={}",
                    taskId,
                    branch,
                    cloneDir);
            return new ParkDeliveryVerdict.Delivered();
        }
        if (remoteTip.carries(cloneDir, branch, tip.get())) {
            return new ParkDeliveryVerdict.Delivered();
        }

        GitCommandResult result = push.push(cloneDir, branch);
        if (result.exitCode() != 0) {
            log.warn(
                    "park delivery push failed, re-attempting once: taskId={}, branch={}, stderr={}",
                    taskId,
                    branch,
                    result.stderr().trim());
            result = push.push(cloneDir, branch);
        }
        if (result.exitCode() != 0) {
            log.warn(
                    "park delivery fence exhausted, parking anyway: taskId={}, branch={}, stderr={}",
                    taskId,
                    branch,
                    result.stderr().trim());
            // The note names the action too (UX2): a human reading a park is deciding what to do
            // next, and "origin is behind" is only actionable once they know that resuming this task
            // elsewhere would read stale state until the branch is pushed.
            return new ParkDeliveryVerdict.Undelivered(
                    "Note: origin is behind this park — branch " + branch
                            + " could not be pushed, so the remote does not yet carry the recorded outcome."
                            + " Push it from this machine (git push origin " + branch
                            + ") before resuming this task elsewhere; until then another instance would resume from stale state.");
        }
        return new ParkDeliveryVerdict.Delivered();
    }
}
