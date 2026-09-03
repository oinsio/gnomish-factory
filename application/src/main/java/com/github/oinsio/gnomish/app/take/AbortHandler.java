package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The infrastructure-abort protocol shared by both abort triggers (design D3,
 * D10): an engine {@code Aborted} outcome (durable persist failed) and an
 * uncaught exception of the take run itself. Both funnel into the identical
 * best-effort protocol — ERROR log, then either {@code recordAbort} (below the
 * fuse) or {@code park(INFRA)} (fuse tripped) — so this class exposes one entry
 * point, {@link #handle}, taking a free-text {@code cause} plus the last known
 * {@link TaskState}, rather than two separate methods per trigger; the caller
 * (a later wiring task) reduces either trigger to that shape before calling in.
 *
 * <p>Best-effort covers BOTH tracker writes, not just the below-fuse {@code
 * recordAbort} (NFR-R2): the fuse-tripping {@code park(INFRA)} is equally
 * wrapped, so a dead tracker at the fuse threshold still yields an {@code
 * AwaitingHuman} result instead of an exception escaping {@link #handle}.
 *
 * <p>The K-fuse decision is a pure threshold comparison over already-fetched
 * {@link AbortFacts} (design D10): {@code facts.count() + 1 >= threshold}.
 * That count is the unified recovery accounting, not a fuse of its own (FR14,
 * design D9 of harden-task-branch-contract): an instance crash and a failed
 * branch repair spend from the same counter and trip the same threshold, and
 * the {@link RecoveryCause} the caller names only decides which share of the
 * history the quarantine report attributes the attempt to.
 * Fetching the current facts, computing backoff delay, and resetting the
 * counter on durable progress are NOT this class's job (tasks 5.4-5.6) — it
 * only receives facts and decides/acts on the single abort at hand.
 *
 * <p>The ERROR log always happens, unconditionally of what the tracker call
 * does or throws (NFR-R2, "a dead tracker never blocks the abort itself"): a
 * dead tracker must not suppress the one observability signal an operator has
 * when the coordination layer itself is unreachable.
 *
 * <p>Implements FR14, NFR-R2, NFR-C1 of add-tracker-port.
 *
 * @param tracker the tracker port used for the best-effort {@code
 *     recordAbort}/{@code park(INFRA)} write; never null
 * @param clock supplies the abort timestamp recorded in {@link AbortRecord};
 *     never null
 */
public record AbortHandler(Tracker tracker, Clock clock) {

    private static final Logger log = LoggerFactory.getLogger(AbortHandler.class);

    /**
     * Runs the best-effort abort protocol for one infrastructure abort (design
     * D3, D10): logs ERROR unconditionally, then either records the abort and
     * returns the task to {@code Ready} (below the fuse) or parks it as {@code
     * AwaitingHuman(INFRA)} with the abort history in the report (fuse tripped).
     * A tracker-call failure on EITHER branch — {@code recordAbort} below the
     * fuse or {@code park(INFRA)} at it — is caught, logged, and does not
     * propagate; {@code handle} always returns the matching {@link TakeResult}
     * (NFR-R2).
     *
     * <p>Implements FR14, NFR-R2, NFR-C1 of add-tracker-port.
     *
     * @param ref the aborting task's identity; never null
     * @param finalState the last known task state; never null
     * @param cause free-text description of what went wrong; never blank
     * @param facts the task's current abort facts, already fetched by the
     *     caller; never null
     * @param threshold the configured abort-fuse threshold (K); positive
     * @param instanceId this factory instance's identity; never null
     * @param category which category of the unified accounting this attempt
     *     spends — a crashed run or a failed branch repair; never null
     * @return {@link TakeResult.Aborted} below the fuse, {@link
     *     TakeResult.AwaitingHuman} with {@link ParkReason#INFRA} at the fuse
     */
    public TakeResult handle(
            TaskRef ref,
            TaskState finalState,
            String cause,
            AbortFacts facts,
            int threshold,
            InstanceId instanceId,
            RecoveryCause category) {
        log.error(
                OperatorEvent.INFRASTRUCTURE_ABORT.head() + "Infrastructure abort on task {} ({}): {}",
                ref.id(),
                category.wireValue(),
                cause);

        var nextCount = facts.count() + 1;
        if (nextCount >= threshold) {
            var report = AbortReportBuilder.build(cause, category, facts, threshold);
            parkBestEffort(ref, report);
            return new TakeResult.AwaitingHuman(finalState, ParkReason.INFRA, report);
        }

        recordAbortBestEffort(ref, cause, instanceId, category);
        return new TakeResult.Aborted(finalState, cause);
    }

    /**
     * The crash-category entry point kept for the callers whose attempt can only be an instance
     * crash — an engine {@code Aborted} outcome, whose durable persist failed inside a running
     * round.
     */
    public TakeResult handle(
            TaskRef ref, TaskState finalState, String cause, AbortFacts facts, int threshold, InstanceId instanceId) {
        return handle(ref, finalState, cause, facts, threshold, instanceId, RecoveryCause.INSTANCE_CRASH);
    }

    /**
     * Parks the fuse-tripped task as {@code AwaitingHuman(INFRA)}, catching and
     * logging (but never propagating) any exception the tracker call throws
     * (NFR-R2): a dead tracker at the fuse threshold must not turn the fuse trip
     * into an escaping exception, since the caller still needs the {@link
     * TakeResult.AwaitingHuman} back — the take run has decided to stop for a
     * human either way, and the report is already carried in that result.
     */
    private void parkBestEffort(TaskRef ref, String report) {
        try {
            tracker.park(ref, ParkReason.INFRA, report);
        } catch (RuntimeException e) {
            log.error(
                    OperatorEvent.ABORT_PARK_FAILED.head()
                            + "park(INFRA) failed for task {}; parking the run for a human anyway",
                    ref.id(),
                    e);
        }
    }

    /**
     * Persists the abort marker, catching and logging (but never propagating) any
     * exception the tracker call throws (NFR-R2): a dead tracker must not block
     * the abort protocol itself, since the caller still needs a {@link
     * TakeResult} back regardless of whether the tracker write landed.
     */
    private void recordAbortBestEffort(TaskRef ref, String cause, InstanceId instanceId, RecoveryCause category) {
        try {
            tracker.recordAbort(ref, new AbortRecord(cause, instanceId.value(), clock.instant(), category));
        } catch (RuntimeException e) {
            log.error(
                    OperatorEvent.ABORT_RECORD_FAILED.head()
                            + "recordAbort failed for task {}; proceeding with the abort anyway",
                    ref.id(),
                    e);
        }
    }
}
