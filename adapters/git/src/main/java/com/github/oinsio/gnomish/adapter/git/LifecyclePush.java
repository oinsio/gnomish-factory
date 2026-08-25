package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The best-effort push that follows every task lifecycle commit (FR1, FR2, NFR-O1 of
 * fix-lifecycle-push) — the policy half of the lifecycle decorators {@link
 * PushBestEffortTaskRepository} and {@link PushBestEffortTaskLifecycleStore}, kept in its own file
 * so both hold delegation shims only.
 *
 * <p>Same discipline as the round-boundary push ({@link BestEffortPush}) and revocation's ({@link
 * BranchPush}), over the same shared primitives: with no {@code origin} configured the run is
 * purely local and this is a silent no-op (UX3); with {@code origin} configured a failed push logs
 * exactly one WARN naming the task, branch, and lifecycle event, then returns normally. It never
 * throws and never retries — durability is the recorded branch state, and a lifecycle write that
 * already succeeded must not be undone by a network failure (NFR-R1).
 *
 * <p>Because the push runs before the decorated lifecycle call returns, a caller that proceeds to
 * a tracker write does so after the replication attempt, never before it (FR2).
 *
 * <p>The WARN names what actually happened (FR8 of bound-subprocess-commands): a push killed on
 * its deadline and a push cut short by a shutdown each say so through {@link PushOutcome} rather
 * than borrowing the rejected push's wording.
 *
 * <p>Implements FR1, FR2, NFR-O1, NFR-R1 of fix-lifecycle-push; FR8, NFR-O2, UX3 of
 * bound-subprocess-commands.
 */
final class LifecyclePush {

    private static final Logger log = LoggerFactory.getLogger(LifecyclePush.class);

    private final OriginRemote origin;
    private final RefspecPush push;

    LifecyclePush(GitProcessRunner runner) {
        this.origin = new OriginRemote(runner);
        this.push = new RefspecPush(runner);
    }

    /**
     * Pushes {@code branch} best-effort right after {@code event}'s commit landed on it.
     *
     * @param taskId the task whose lifecycle commit was just recorded, for the WARN context
     * @param event the lifecycle event that produced the commit, for the WARN context
     * @param repo the clone the push runs from; never null
     * @param branch the task branch name; never blank
     */
    void pushAfter(String taskId, String event, Path repo, String branch) {
        if (!origin.isConfigured(repo)) {
            return;
        }
        GitCommandResult result = push.push(repo, branch);
        String outcome = PushOutcome.describe("lifecycle push", result);
        if (outcome != null) {
            log.warn(
                    "{}: taskId={}, branch={}, event={}, stderr={}",
                    outcome,
                    taskId,
                    branch,
                    event,
                    result.stderr().trim());
        }
    }
}
