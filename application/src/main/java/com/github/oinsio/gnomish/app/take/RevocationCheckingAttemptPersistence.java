package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import java.util.Optional;

/**
 * Decorates the engine's {@link AttemptPersistence} port with the round-boundary revocation check
 * (design D2, FR15): every durable write — a round's {@link #persist} and an open round's {@link
 * #persistPending} (design D21 of migrate-verify-to-executor-contract) alike — is bracketed by the
 * run's {@link ClaimBoundaryCheck}: its gate before the delegate writes, its "still ours and alive"
 * judgement after. A negative answer throws {@link RevocationDetectedException} and records it in
 * {@link #revocation()}; from then on every write through this instance throws before its delegate.
 *
 * <p>The throw is NOT how the caller learns of a revocation: {@code AttemptJournal#commit} — the
 * engine's per-round persist-and-events plumbing — catches every {@link RuntimeException} thrown by
 * {@link #persist} and turns it into {@link com.github.oinsio.gnomish.domain.engine.TaskOutcome
 * .Aborted} (see {@link AttemptPersistence}'s contract), and a throwing {@link #persistPending} makes
 * its check cannot-verify. The throw still matters: it stops the engine's attempt loop at the
 * revoked round. The take runner detects the revocation by querying {@link #revocation()} AFTER
 * {@code engine.run(...)} returns. One instance per run, so the recorded revocation is not shared
 * mutable state of the engine.
 *
 * <p>The judgement runs strictly after the delegate's write, never in place of it, so a revocation
 * found here finds the write already durable — nothing this decorator does can lose work. The one
 * exception is the self-fencing freeze (FR13 of harden-task-branch-contract), consulted BEFORE the
 * write: a holder that no longer knows its claim is live writes nothing until it re-verifies.
 *
 * <p>Implements FR15, D2 of add-tracker-port. Implements FR2, NFR-R1, NFR-O1 of
 * fix-abort-progress-reset. Implements FR8, D7 of add-claim-heartbeat. Implements FR11, D9 of
 * add-factory-serve. Implements FR13 of harden-task-branch-contract; FR20 of
 * migrate-verify-to-executor-contract.
 */
public final class RevocationCheckingAttemptPersistence implements AttemptPersistence {

    private final AttemptPersistence delegate;
    private final ClaimBoundaryCheck boundary;

    /**
     * @param delegate the underlying persistence that actually makes each write durable; never null
     * @param tracker the tracker port used for the post-write "still ours and alive" check; never
     *     null
     * @param ref the task being run under this persistence; never null
     * @param instanceId this factory instance's identity, compared against the reported claim
     *     holder; never null
     * @param claimLossFlag the per-run heartbeat claim-loss flag (design D7, FR8 of
     *     add-claim-heartbeat), consulted at each boundary beside the {@code fetchTask} check; never
     *     null
     */
    public RevocationCheckingAttemptPersistence(
            AttemptPersistence delegate,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId,
            ClaimLossFlag claimLossFlag) {
        this.delegate = delegate;
        this.boundary = new ClaimBoundaryCheck(tracker, ref, instanceId, claimLossFlag);
    }

    /**
     * The heartbeat-flag-free construction used by the revocation and lifecycle specs that predate
     * the claim-loss flag: delegates with a fresh empty {@link ClaimLossFlag} that never trips, so
     * the boundary decision reduces to the {@code fetchTask} check exactly as before.
     */
    public RevocationCheckingAttemptPersistence(
            AttemptPersistence delegate, Tracker tracker, TaskRef ref, InstanceId instanceId) {
        this(delegate, tracker, ref, instanceId, new ClaimLossFlag());
    }

    /**
     * Delegates the round's durable persist inside the run's boundary judgement.
     *
     * @throws RevocationDetectedException if a revocation was recorded earlier, or the task is no
     *     longer claimed by this instance, closed, or otherwise moved by a human or another instance
     */
    @Override
    public void persist(String taskId, TaskState state, ToolTrace trace) {
        boundary.beforeWrite(taskId);
        delegate.persist(taskId, state, trace);
        boundary.afterWrite(taskId);
    }

    /**
     * Delegates the open round's pending write inside the same judgement as {@link #persist}: a
     * revocation found here is recorded, and no later write passes this decorator.
     *
     * @throws RevocationDetectedException as {@link #persist}
     */
    @Override
    public void persistPending(String taskId, TaskState state) {
        boundary.beforeWrite(taskId);
        delegate.persistPending(taskId, state);
        boundary.afterWrite(taskId);
    }

    /**
     * Reports whether a boundary check detected a revocation during this instance's lifetime, for
     * the take runner to query AFTER {@code engine.run(...)} returns.
     *
     * @return the detected {@link RevocationDetectedException}, or empty if every check so far passed
     */
    public Optional<RevocationDetectedException> revocation() {
        return boundary.revocation();
    }
}
