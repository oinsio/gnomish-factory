package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorates the engine's {@link AttemptPersistence} port with the round-boundary revocation check
 * (design D2, FR15): after the delegate durably persists a round, one {@code fetchTask} asks the
 * tracker whether the task is still ours and alive — not closed, claim intact, state unchanged by
 * a human. A negative answer throws {@link RevocationDetectedException} and records it in {@link
 * #revocation()}.
 *
 * <p>The throw is NOT how the caller learns of a revocation: {@code
 * AttemptJournal#commit} — the engine's per-round
 * persist-and-events plumbing — catches every {@link RuntimeException} thrown by {@link #persist},
 * this one included, and turns it into a {@link com.github.oinsio.gnomish.domain.engine.TaskOutcome
 * .Aborted} by design (see {@link AttemptPersistence}'s own contract: "an implementation that
 * cannot make the round durable signals it by throwing"), so {@code RevocationDetectedException}
 * never reaches a caller as a thrown exception through {@code Engine.run}. The throw still matters
 * internally — it is what makes the engine's attempt loop stop starting further rounds at the
 * revoked round rather than continuing past it — but detection by the take runner happens by
 * querying {@link #revocation()} on this instance AFTER {@code engine.run(...)} returns, not via a
 * catch block. This decorator is constructed fresh per {@code engine.run(...)} call (one instance
 * per run), so recording the detected revocation in an instance field is safe and does not
 * introduce shared mutable state into the engine itself.
 *
 * <p>The check runs strictly after the delegate's {@link #persist}, never before or in place of
 * it: the round boundary's durable hook is the persisted round itself, so a revocation discovered
 * here always finds the round already safely committed — nothing this decorator does can lose
 * work, only stop further rounds from starting.
 *
 * <p>"Still ours and alive" is exactly: {@link TrackerTaskState.Working} held by this instance's
 * own {@link InstanceId}. Any other state — {@link TrackerTaskState.Gone} (closed or nonexistent),
 * {@link TrackerTaskState.AwaitingHuman} or {@link TrackerTaskState.Ready} (a human or another
 * process moved the task), or {@link TrackerTaskState.Working} held by a different instance — is a
 * revocation. {@link TrackerTaskState.Finished} is treated the same way: this decorator only ever
 * wraps a persistence used during an active take run, so a task already {@code Finished} can only
 * mean it was moved out from under this run.
 *
 * <p>Once per run, on the first successful delegate persist, this decorator also emits a
 * durable-progress marker via {@link Tracker#recordProgress(TaskRef)} — before the revocation
 * check above (design D2) — so that an abort on a LATER round of the same run is recognized as
 * "after durable progress" and does not over-count toward the abort fuse. The emission is
 * strictly best-effort (FR2, NFR-R1, NFR-O1): the round is already durable by the time it runs,
 * so a failed marker only risks a later over-count, never lost work. A thrown {@link
 * RuntimeException} from {@code recordProgress} is caught, logged at WARN with the task ref, and
 * swallowed — the run proceeds unchanged. The once-per-run guard is set BEFORE the call is known
 * to succeed and is never reset on failure, so {@code recordProgress} is attempted at most once
 * per {@code engine.run(...)}, whether that attempt succeeds or throws.
 *
 * <p>The same boundary is the one authoritative "still ours" decision for the heartbeat's
 * claim-loss flag (design D7, FR8 of add-claim-heartbeat): a beat that answered {@code ClaimGone}
 * sets a {@link ClaimLossFlag}, and {@link #persist} consults it here beside the {@code fetchTask}
 * check, so a lost claim throws the very same {@link RevocationDetectedException} and takes the
 * revocation-identical reaction (salvage, best-effort push, release, no park/finish/abort). An
 * empty flag never trips, so a run without a live heartbeat behaves exactly as before.
 *
 * <p>Implements FR15, D2 of add-tracker-port. Implements FR2, NFR-R1, NFR-O1 of
 * fix-abort-progress-reset. Implements FR8, D7 of add-claim-heartbeat.
 */
public final class RevocationCheckingAttemptPersistence implements AttemptPersistence {

    private static final Logger log = LoggerFactory.getLogger(RevocationCheckingAttemptPersistence.class);

    private final AttemptPersistence delegate;
    private final Tracker tracker;
    private final TaskRef ref;
    private final InstanceId instanceId;
    private final ClaimLossFlag claimLossFlag;
    private volatile @Nullable RevocationDetectedException detected;
    private volatile boolean progressRecorded;

    /**
     * @param delegate the underlying persistence that actually makes each round durable; never null
     * @param tracker the tracker port used for the post-persist "still ours and alive" check; never
     *     null
     * @param ref the task being run under this persistence; never null
     * @param instanceId this factory instance's identity, compared against the reported claim
     *     holder; never null
     * @param claimLossFlag the per-run heartbeat claim-loss flag (design D7, FR8 of
     *     add-claim-heartbeat): consulted at each round boundary in addition to the {@code
     *     fetchTask} check, so a claim a beat already proved gone is reacted to as a revocation
     *     without waiting for the tracker read to catch up; never null
     */
    public RevocationCheckingAttemptPersistence(
            AttemptPersistence delegate,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId,
            ClaimLossFlag claimLossFlag) {
        this.delegate = delegate;
        this.tracker = tracker;
        this.ref = ref;
        this.instanceId = instanceId;
        this.claimLossFlag = claimLossFlag;
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
     * Delegates the round's durable persist first, then — on the first round of this run only —
     * best-effort emits a durable-progress marker via {@link Tracker#recordProgress(TaskRef)}
     * (FR2, D2), then performs the "still ours and alive" tracker check; a failed check records
     * the failure (see {@link #revocation()}) and throws after the round is already safely
     * committed. The throw is what stops the engine's attempt loop from starting a further round —
     * see the class doc for why it never reaches a caller of {@code engine.run(...)} as a thrown
     * exception.
     *
     * @throws RevocationDetectedException if the task is no longer claimed by this instance,
     *     closed, or otherwise moved by a human or another instance
     */
    @Override
    public void persist(String taskId, TaskState state, ToolTrace trace) {
        delegate.persist(taskId, state, trace);

        // FR8, design D7: a beat that answered "claim gone" flags the loss for the nearest round
        // boundary. Consulted BEFORE recordProgressOnce and the fetchTask read so a lost claim
        // writes no further tracker state and takes the revocation-identical reaction immediately —
        // the beat's 404 is authoritative even when this instance's (ETag-cached) fetchTask would
        // still report the claim as ours.
        if (claimLossFlag.isLost(ref)) {
            var exception = new RevocationDetectedException(taskId, "claim marker gone (heartbeat reported loss)");
            detected = exception;
            throw exception;
        }

        recordProgressOnce();

        TrackerTaskState current = tracker.fetchTask(ref).state();
        if (!(current instanceof TrackerTaskState.Working(String holder)) || !holder.equals(instanceId.value())) {
            var exception = new RevocationDetectedException(taskId, describe(current));
            detected = exception;
            throw exception;
        }
    }

    /**
     * Emits the run's durable-progress marker at most once, on the first call, regardless of
     * whether that attempt succeeds or throws (FR2, D2). Best-effort: a {@link RuntimeException}
     * from {@link Tracker#recordProgress(TaskRef)} is caught, logged at WARN with the task ref,
     * and swallowed — the round is already durable, so the only risk of a failed marker is a
     * later over-count, never lost work (NFR-R1, NFR-O1).
     */
    private void recordProgressOnce() {
        if (progressRecorded) {
            return;
        }
        progressRecorded = true;
        try {
            tracker.recordProgress(ref);
        } catch (RuntimeException e) {
            log.warn("recordProgress failed for task {}; proceeding with the run anyway", ref.id(), e);
        }
    }

    /**
     * Reports whether a round-boundary "still ours and alive" check detected a revocation during
     * this instance's lifetime, for the take runner to query AFTER {@code engine.run(...)}
     * returns — see the class doc for why this, not a caught exception, is the correct detection
     * mechanism.
     *
     * @return the detected {@link RevocationDetectedException} if the task was found no longer
     *     ours and alive at any round boundary; empty if every check so far has passed
     */
    public Optional<RevocationDetectedException> revocation() {
        return Optional.ofNullable(detected);
    }

    private static String describe(TrackerTaskState state) {
        return switch (state) {
            case TrackerTaskState.Gone gone ->
                gone.closureReason() == null
                        ? "task closed or nonexistent"
                        : "task closed or nonexistent (" + gone.closureReason() + ")";
            case TrackerTaskState.AwaitingHuman awaitingHuman ->
                "task parked awaiting human (" + awaitingHuman.reason() + ")";
            case TrackerTaskState.Ready ignored -> "task released back to ready";
            case TrackerTaskState.Finished ignored -> "task already finished";
            case TrackerTaskState.Working working -> "claim held by another instance (" + working.holder() + ")";
        };
    }
}
