package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The best-effort closing push outside the lifecycle store and the host round (design D13 of
 * tighten-spec-contract): a sandboxed round's admitted state commit ({@link
 * PushBestEffortAttemptPersistence}) and the push that follows a salvage, in either medium ({@link
 * GitTaskBranches#pushClosingBestEffort}, and the container support). The branch and the register are
 * pushed as the clone holds them — a closing commit already moved both together in its own ref
 * transaction, and a salvage that found nothing to commit moved neither — in one atomic push
 * ({@link ClosingPush}).
 *
 * <p>Same discipline as every best-effort push point: with no {@code origin} configured the push is
 * a silent no-op; a failed push logs one WARN and returns normally — never a throw, a retry or a
 * force — because the commit is already durable in the clone, and {@link OriginReconciliation}
 * delivers what a push missed at the task's next touchpoint. A push killed on its deadline or cut
 * short by a shutdown says so through {@link PushOutcome}.
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract; FR15 of add-tracker-port; FR5 of
 * add-sandbox-core; FR8, NFR-O2, UX3 of bound-subprocess-commands.
 */
// Not a record: a behavior-bearing push service, like its sibling LifecyclePush.
@SuppressWarnings("ClassCanBeRecord")
public final class BestEffortClosingPush {

    private static final Logger log = LoggerFactory.getLogger(BestEffortClosingPush.class);

    private final OriginRemote origin;
    private final ClosingPush push;

    /**
     * @param runner the git subprocess seam; never null
     */
    public BestEffortClosingPush(GitRunner runner) {
        this.origin = new OriginRemote(runner);
        this.push = new ClosingPush(runner);
    }

    /**
     * Pushes the task branch and its register to {@code origin} as the clone holds them,
     * atomically and best-effort.
     *
     * @param repo the clone, or a worktree of it; git runs with this path as {@code cwd}
     * @param taskId the tracker's original taskId; names the branch and the register
     */
    public void pushBestEffort(Path repo, String taskId) {
        if (!origin.isConfigured(repo)) {
            return;
        }
        GitCommandResult result = push.push(repo, taskId);
        String outcome = PushOutcome.describe("closing push", result);
        if (outcome != null) {
            log.warn(
                    OperatorEvent.CLOSING_PUSH_FAILED.head()
                            + "{}: taskId={}, stderr={}; the commit and its register stay in the clone, and"
                            + " the task's next touchpoint delivers them to origin",
                    outcome,
                    taskId,
                    result.stderr().forLog());
        }
    }
}
