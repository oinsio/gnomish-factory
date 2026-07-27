package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.ToolTrace;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
 * <p>Implements FR15, D2 of add-tracker-port.
 */
public final class RevocationCheckingAttemptPersistence implements AttemptPersistence {

    private final AttemptPersistence delegate;
    private final Tracker tracker;
    private final TaskRef ref;
    private final InstanceId instanceId;
    private volatile @Nullable RevocationDetectedException detected;

    /**
     * @param delegate the underlying persistence that actually makes each round durable; never null
     * @param tracker the tracker port used for the post-persist "still ours and alive" check; never
     *     null
     * @param ref the task being run under this persistence; never null
     * @param instanceId this factory instance's identity, compared against the reported claim
     *     holder; never null
     */
    public RevocationCheckingAttemptPersistence(
            AttemptPersistence delegate, Tracker tracker, TaskRef ref, InstanceId instanceId) {
        this.delegate = delegate;
        this.tracker = tracker;
        this.ref = ref;
        this.instanceId = instanceId;
    }

    /**
     * Delegates the round's durable persist first, then performs the "still ours and alive"
     * tracker check; a failed check records the failure (see {@link #revocation()}) and throws
     * after the round is already safely committed. The throw is what stops the engine's attempt
     * loop from starting a further round — see the class doc for why it never reaches a caller of
     * {@code engine.run(...)} as a thrown exception.
     *
     * @throws RevocationDetectedException if the task is no longer claimed by this instance,
     *     closed, or otherwise moved by a human or another instance
     */
    @Override
    public void persist(String taskId, TaskState state, ToolTrace trace) {
        delegate.persist(taskId, state, trace);

        TrackerTaskState current = tracker.fetchTask(ref).state();
        if (!(current instanceof TrackerTaskState.Working(String holder)) || !holder.equals(instanceId.value())) {
            var exception = new RevocationDetectedException(taskId, describe(current));
            detected = exception;
            throw exception;
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
