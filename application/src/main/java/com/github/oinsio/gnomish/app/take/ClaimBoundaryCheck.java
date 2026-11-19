package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The "still ours and alive" judgement of one run at its durable writes (design D2, FR15 of
 * add-tracker-port), the decision {@link RevocationCheckingAttemptPersistence} sequences around its
 * delegate's round and pending writes. It owns the run's tracker reads, the claim-loss flag it
 * consults, the once-per-run progress marker, and the revocation it recorded.
 *
 * <p>"Still ours and alive" is exactly {@link TrackerTaskState.Working} held by this instance's own
 * {@link InstanceId}. Any other state — {@link TrackerTaskState.Gone}, {@link
 * TrackerTaskState.AwaitingHuman}, {@link TrackerTaskState.Ready}, {@link TrackerTaskState.Working}
 * held by another instance, and {@link TrackerTaskState.Finished} (a take run's task can only be
 * finished out from under it) — is a revocation.
 *
 * <ul>
 *   <li>{@link #beforeWrite} — first, a run that already recorded a revocation writes nothing more:
 *       every later write throws before its delegate (design D21 of
 *       migrate-verify-to-executor-contract, so a check's cannot-verify never lands a round commit
 *       after a revocation). Then the self-fencing freeze (FR13 of harden-task-branch-contract):
 *       while the claim is unconfirmed, one {@code fetchTask} must say the claim is still ours
 *       before anything is written; a confirming answer lifts the freeze.
 *   <li>{@link #afterWrite} — the claim-loss flag first (FR8, D7 of add-claim-heartbeat: a beat's
 *       "claim gone" is authoritative, and the flag's {@link ClaimLossFlag#reason} words a graceful
 *       {@code serve} shutdown too, FR11, D9 of add-factory-serve), then the run's durable-progress
 *       marker, at most once and best-effort (FR2, NFR-R1, NFR-O1 of fix-abort-progress-reset:
 *       recorded before the read, so an abort on a later round is "after durable progress"), then
 *       one {@code fetchTask}.
 * </ul>
 *
 * <p>A revocation throws {@link RevocationDetectedException} and is recorded in {@link
 * #revocation()}: the engine turns the throw into {@code Aborted}, and the take runner learns of the
 * revocation by asking after {@code engine.run(...)} returns. One instance per run, so the recorded
 * state is never shared between runs.
 *
 * <p>Implements FR15, D2 of add-tracker-port; FR2, NFR-R1, NFR-O1 of fix-abort-progress-reset; FR8,
 * D7 of add-claim-heartbeat; FR11, D9 of add-factory-serve; FR13 of harden-task-branch-contract;
 * FR20 of migrate-verify-to-executor-contract.
 */
final class ClaimBoundaryCheck {

    private static final Logger log = LoggerFactory.getLogger(ClaimBoundaryCheck.class);

    private final Tracker tracker;
    private final TaskRef ref;
    private final InstanceId instanceId;
    private final ClaimLossFlag claimLossFlag;
    private volatile @Nullable RevocationDetectedException detected;
    private volatile boolean progressRecorded;

    ClaimBoundaryCheck(Tracker tracker, TaskRef ref, InstanceId instanceId, ClaimLossFlag claimLossFlag) {
        this.tracker = tracker;
        this.ref = ref;
        this.instanceId = instanceId;
        this.claimLossFlag = claimLossFlag;
    }

    /**
     * The gate before a durable write.
     *
     * @throws RevocationDetectedException if a revocation was already recorded, or the frozen claim
     *     did not re-verify as ours
     */
    void beforeWrite(String taskId) {
        RevocationDetectedException earlier = detected;
        if (earlier != null) {
            throw earlier;
        }
        if (!claimLossFlag.isUnconfirmed(ref)) {
            return;
        }
        TrackerTaskState current = tracker.fetchTask(ref).state();
        if (ours(current)) {
            log.info("claim for {} re-verified at the round boundary; writes resume", ref.id());
            claimLossFlag.confirmedByReverification(ref);
            return;
        }
        throw revoked(taskId, RevocationReason.describe(current));
    }

    /**
     * The judgement after a durable write.
     *
     * @throws RevocationDetectedException if the claim was lost or the task is no longer ours
     */
    void afterWrite(String taskId) {
        if (claimLossFlag.isLost(ref)) {
            throw revoked(taskId, claimLossFlag.reason(ref));
        }
        recordProgressOnce();
        TrackerTaskState current = tracker.fetchTask(ref).state();
        if (!ours(current)) {
            throw revoked(taskId, RevocationReason.describe(current));
        }
    }

    /** The revocation recorded at any boundary of this run, if one was. */
    Optional<RevocationDetectedException> revocation() {
        return Optional.ofNullable(detected);
    }

    private boolean ours(TrackerTaskState current) {
        return current instanceof TrackerTaskState.Working(String holder) && holder.equals(instanceId.value());
    }

    private RevocationDetectedException revoked(String taskId, String reason) {
        var exception = new RevocationDetectedException(taskId, reason);
        detected = exception;
        return exception;
    }

    private void recordProgressOnce() {
        if (progressRecorded) {
            return;
        }
        progressRecorded = true;
        try {
            tracker.recordProgress(ref);
        } catch (RuntimeException e) {
            log.warn(
                    OperatorEvent.RECORD_PROGRESS_FAILED.head()
                            + "recordProgress failed for task {}; proceeding with the run anyway",
                    ref.id(),
                    e);
        }
    }
}
