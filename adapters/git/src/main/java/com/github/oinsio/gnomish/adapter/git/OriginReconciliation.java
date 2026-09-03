package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.DivergenceOutcome;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The level-based half of replication (FR3 of fix-lifecycle-push): at a task touchpoint — resume
 * start, a run's terminal boundary — the factory compares what {@code origin} holds for the task
 * branch against the local tip and pushes best-effort when origin is behind. A push an earlier
 * instance missed to a crash or an outage is therefore delivered by the next instance that touches
 * the task, on whichever machine that is; the edge-triggered lifecycle push ({@link LifecyclePush})
 * covers the normal path, and this covers what the edge lost.
 *
 * <p>The local tip is a parameter, never read here (design D3): host callers supply it from their
 * worktree-side reader and container callers from {@code GitObjects.resolveRef}, so no third
 * "read the branch tip" implementation appears. The check costs exactly one remote-refs read when
 * origin is already current (NFR-C1) — the ancestry question is answered locally, and only for a
 * tip that differs.
 *
 * <p>Never blocks and never throws: no {@code origin} is a silent no-op, an unreachable origin or a
 * failed catch-up push is one WARN, and the run proceeds either way (NFR-R1).
 *
 * <p>Implements FR3, NFR-R1, NFR-O1, NFR-C1 of fix-lifecycle-push; FR8 of
 * harden-task-branch-contract.
 */
public final class OriginReconciliation {

    private static final Logger log = LoggerFactory.getLogger(OriginReconciliation.class);

    private final OriginRemote origin;
    private final RemoteBranchTip remoteTip;
    private final RefspecPush push;

    /**
     * @param runner the git subprocess seam; never null
     */
    public OriginReconciliation(GitProcessRunner runner) {
        this.origin = new OriginRemote(runner);
        this.remoteTip = new RemoteBranchTip(runner);
        this.push = new RefspecPush(runner);
    }

    /**
     * Brings {@code origin} up to {@code localTip} for {@code branch} when it is behind or does not
     * carry the branch at all.
     *
     * @param taskId the task being touched, for log context; never blank
     * @param touchpoint what triggered the check ({@code resume-start}, {@code terminal-boundary}),
     *     for log context; never blank
     * @param repo the clone the reads and the push run from; never null
     * @param branch the task branch name; never blank
     * @param localTip the tip the caller's own mode-native reader resolved for {@code branch}
     */
    public void reconcile(String taskId, String touchpoint, Path repo, String branch, String localTip) {
        if (!origin.isConfigured(repo)) {
            return;
        }
        Optional<String> remote = remoteTip.read(repo, branch);
        // The relation is computed in one place for all three of its askers (design D8): this
        // touchpoint keeps its own cheap ls-remote read — one round trip, no fetch — and hands the
        // two tips it already holds to the shared classifier instead of re-deriving the verdict.
        DivergenceOutcome relation = ReplicaRelation.of(
                localTip,
                remote.orElse(null),
                (candidate, descendant) -> remoteTip.isAncestor(repo, candidate, descendant));
        if (relation == DivergenceOutcome.EQUAL) {
            return;
        }
        if (relation == DivergenceOutcome.BEHIND || relation == DivergenceOutcome.DIVERGED) {
            // Origin holds something the local tip does not descend from — its own line, or one
            // simply unknown to this clone. A fast-forward push cannot fix that, and no touchpoint
            // repairs history (NG4): the resume-time reconciler owns discard-under-lease, this
            // read-only touchpoint does not, so it declines the push and says why.
            log.warn(
                    OperatorEvent.ORIGIN_RECONCILIATION_SKIPPED.head()
                            + "origin reconciliation skipped: taskId={}, branch={}, touchpoint={}, reason=origin tip {} is not"
                            + " an ancestor of the local tip {}",
                    taskId,
                    branch,
                    touchpoint,
                    remote.orElse("(unknown)"),
                    localTip);
            return;
        }
        // An empty read is either "origin does not carry the branch" or "origin was unreachable" —
        // one refs read cannot tell them apart, and the catch-up push is the right answer to both:
        // it delivers in the first case and fails into the WARN below in the second.
        // FR12 of harden-logging-observability: the intention and the outcome of one catch-up
        // push are two lines about one path — the failure WARN below is the one that carries the
        // decision, so the intention stays for whoever is diagnosing, at DEBUG.
        log.debug(
                "origin does not hold the task branch tip, pushing: taskId={}, branch={}, touchpoint={},"
                        + " originTip={}, localTip={}",
                taskId,
                branch,
                touchpoint,
                remote.orElse("(absent or unreachable)"),
                localTip);
        GitCommandResult result = push.push(repo, branch);
        String outcome = PushOutcome.describe("origin reconciliation push", result);
        if (outcome != null) {
            log.warn(
                    OperatorEvent.ORIGIN_RECONCILIATION_FAILED.head()
                            + "{}: taskId={}, branch={}, touchpoint={}, stderr={}",
                    outcome,
                    taskId,
                    branch,
                    touchpoint,
                    LogText.forLog(result.stderr()));
        }
    }
}
