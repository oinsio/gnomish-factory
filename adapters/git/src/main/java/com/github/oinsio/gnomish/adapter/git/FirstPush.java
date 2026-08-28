package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.RemoteBranchTip.Carriage;
import com.github.oinsio.gnomish.app.port.git.FirstPushFailedException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one push in the factory that is load-bearing (FR7): a newly created task branch's first
 * delivery to {@code origin}. Everything after it is best-effort ({@link LifecyclePush}, {@link
 * BestEffortPush}, {@link BranchPush}) because the recorded branch state is the durability
 * boundary and a missed push is caught up at the next touchpoint — but a branch origin has never
 * seen has no next touchpoint on any other instance, so a claim must not proceed on one.
 *
 * <p>A push that did not run to its own exit established nothing about the remote (design D14), so
 * this never re-pushes on a guess: after any unsuccessful attempt it asks {@code origin} whether
 * the local tip is already there, and a confirmed landed ref is success — a timed-out push that
 * actually delivered must not be retried into a spurious abort. Only when the remote demonstrably
 * lacks the tip, or would not say, does another attempt run, under the same infrastructure budget
 * the locate fetch uses ({@link GitInfrastructureRetry}) — never a bounded re-attempt of the kind
 * {@code bound-subprocess-commands} forbids spending on a timed-out invocation.
 *
 * <p>With no {@code origin} configured the run is purely local and this is a silent no-op, exactly
 * like its best-effort siblings: there is nothing to be load-bearing about (UX3).
 *
 * <p>Implements FR7 of harden-task-branch-contract.
 */
final class FirstPush {

    private static final Logger log = LoggerFactory.getLogger(FirstPush.class);

    private final OriginRemote origin;
    private final RefspecPush push;
    private final LocalBranchTip localTip;
    private final RemoteBranchTip remoteTip;
    private final GitInfrastructureRetry retry;

    FirstPush(GitProcessRunner runner) {
        this(runner, GitInfrastructureRetry.system());
    }

    FirstPush(GitProcessRunner runner, GitInfrastructureRetry retry) {
        this.origin = new OriginRemote(runner);
        this.push = new RefspecPush(runner);
        this.localTip = new LocalBranchTip(runner);
        this.remoteTip = new RemoteBranchTip(runner);
        this.retry = retry;
    }

    /** What one attempt at the load-bearing push established about the branch's delivery. */
    private record Attempt(boolean delivered, String reason) {}

    /**
     * Delivers {@code branch}'s first commit to {@code origin}, retrying until it lands or the
     * budget runs out.
     *
     * @param taskId the task whose branch was just created; never blank
     * @param repo the clone the push runs from; never null
     * @param branch the task branch name; never blank
     * @throws FirstPushFailedException when the branch could not be confirmed on {@code origin}
     */
    void deliver(String taskId, Path repo, String branch) {
        if (!origin.isConfigured(repo)) {
            return;
        }
        Attempt last = retry.until(() -> attempt(repo, branch), Attempt::delivered);
        if (!last.delivered()) {
            throw new FirstPushFailedException(taskId, branch, last.reason());
        }
    }

    private Attempt attempt(Path repo, String branch) {
        GitCommandResult result = push.push(repo, branch);
        String outcome = PushOutcome.describe("first push", result);
        if (outcome == null) {
            return new Attempt(true, "pushed");
        }
        log.warn(
                "{}, re-checking the remote tip: branch={}, stderr={}",
                outcome,
                branch,
                result.stderr().trim());
        return confirmLanded(repo, branch, outcome);
    }

    /**
     * The re-check FR7 requires before any re-push: only {@code origin} can say whether the push
     * that did not report success nevertheless landed.
     */
    private Attempt confirmLanded(Path repo, String branch, String outcome) {
        return localTip.read(repo, branch)
                .map(tip -> switch (remoteTip.confirm(repo, branch, tip)) {
                    case Carriage.CARRIES -> new Attempt(true, "already landed");
                    case Carriage.ABSENT -> new Attempt(false, outcome + " and origin does not carry " + branch);
                    case Carriage.UNKNOWN ->
                        new Attempt(false, outcome + " and origin would not say whether it landed");
                })
                // Nothing local to deliver: the branch this push was created for does not exist,
                // which is a defect in the caller, not a remote outcome worth retrying.
                .orElseGet(() -> new Attempt(false, outcome + " and the clone holds no " + branch + " to deliver"));
    }
}
