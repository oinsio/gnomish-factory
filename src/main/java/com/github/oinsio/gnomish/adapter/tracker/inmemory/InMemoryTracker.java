package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord;
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult;
import com.github.oinsio.gnomish.app.port.tracker.HumanReply;
import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * In-memory reference implementation of the {@link Tracker} port (design D15): the executable example
 * for adapter authors (FR3, G2). Tasks live in one insertion-ordered {@link LinkedHashMap} (queue
 * order for {@link #listReady(int)}); every operation runs under one coarse {@link ReentrantLock} via
 * {@link #withLock}, since {@link #claim(TaskRef, String)}'s check/decide/mutate must be observably
 * atomic (NFR-R1). {@link InMemoryTrackerHarness} adds simulation/seeding hooks over the
 * package-private {@link #store}/{@link #lock}. Every coordination write appends a {@link
 * CorrespondenceEntry} to the task's thread except {@code release} (FR18, M3, UX4, D2). Implements
 * FR1, FR2, FR3, FR18, M3, UX4 of add-tracker-port.
 */
public class InMemoryTracker implements Tracker {

    /** The task store, in insertion (adapter queue) order; guarded by {@link #lock}. */
    final Map<TaskRef, TrackedTask> store = new LinkedHashMap<>();
    /** Guards every read/write of {@link #store} and each {@link TrackedTask}'s mutable fields. */
    final ReentrantLock lock = new ReentrantLock();
    /** Per-adapter monotonic minter for claim markers and their advancing versions (FR5). */
    final ClaimClock claimClock = new ClaimClock();
    /** Lease-maintenance trio (listOpen/heartbeat/removeStaleClaim), split out for file size. */
    final InMemoryLeaseOps leaseOps = new InMemoryLeaseOps(this);
    /** Race-interleaving hook (FR3): run by {@link #claim} before the lock (see harness {@code armClaimGate}). */
    @Nullable
    Runnable claimGate;

    @Override
    public List<ReadyTask> listReady(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("listReady limit must be positive, was " + limit);
        }
        return withLock(() -> store.entrySet().stream()
                .filter(entry -> entry.getValue().state() instanceof TrackerTaskState.Ready)
                .limit(limit)
                .map(entry -> new ReadyTask(entry.getKey(), entry.getValue().abortFacts(), returned(entry.getValue())))
                .toList());
    }

    /**
     * Derives the "returned" fact (FR7 of add-factory-serve) from a task's recorded correspondence
     * history rather than a dedicated field: true when the thread carries a {@code PARK} entry
     * (human-returned: claimed, then given back with a report) or a {@code STALE_CLAIM_REMOVED} entry
     * (reaper-returned); false otherwise, including never-claimed tasks and tasks delivered without
     * either marker (which would not reappear via {@code listReady} anyway).
     */
    private static boolean returned(TrackedTask task) {
        return task.thread().stream()
                .map(CorrespondenceEntry::kind)
                .anyMatch(kind ->
                        kind == CorrespondenceEntry.Kind.PARK || kind == CorrespondenceEntry.Kind.STALE_CLAIM_REMOVED);
    }

    @Override
    public TrackerTask fetchTask(TaskRef ref) {
        return withLock(() -> {
            TrackedTask task = store.get(ref);
            if (task == null) {
                TaskSnapshot gone = new TaskSnapshot(ref.id(), ref.id(), "");
                return new TrackerTask(ref, gone, new TrackerTaskState.Gone(), AbortFacts.none());
            }
            return new TrackerTask(ref, task.snapshot(), task.state(), task.abortFacts());
        });
    }

    @Override
    public List<HumanReply> collectDecisions(TaskRef ref) {
        return withLock(() -> requireTask(ref).decisionsSinceAck());
    }

    @Override
    public ClaimResult claim(TaskRef ref, String instanceId) {
        Runnable gate = claimGate;
        if (gate != null) {
            gate.run();
        }
        return withLock(() -> {
            TrackedTask task = requireTask(ref);
            if (task.state() instanceof TrackerTaskState.Working(String holder)) {
                return new ClaimResult.Held(holder);
            }
            task.state(new TrackerTaskState.Working(instanceId));
            task.establishClaim(claimClock.mint(instanceId));
            task.note(CorrespondenceEntry.Kind.CLAIM, "claimed by " + instanceId);
            return new ClaimResult.Acquired();
        });
    }

    /** Leaves the logical state untouched (design D2, FR15) but drops any claim marker (FR5). */
    @Override
    public void release(TaskRef ref) {
        withLock(() -> requireTask(ref).clearClaim());
    }

    @Override
    public void park(TaskRef ref, ParkReason reason, String report) {
        withLock(() -> {
            TrackedTask task = requireTask(ref);
            task.state(new TrackerTaskState.AwaitingHuman(reason));
            task.clearClaim();
            task.report(report);
            task.note(CorrespondenceEntry.Kind.PARK, "parked (" + reason + "): " + report);
        });
    }

    @Override
    public void finish(TaskRef ref, String summary) {
        withLock(() -> {
            TrackedTask task = requireTask(ref);
            task.state(new TrackerTaskState.Finished());
            task.clearClaim();
            task.summary(summary);
            task.note(CorrespondenceEntry.Kind.FINISH, summary);
        });
    }

    @Override
    public void recordAbort(TaskRef ref, AbortRecord record) {
        withLock(() -> {
            TrackedTask task = requireTask(ref);
            task.recordAbort(record.at());
            task.state(new TrackerTaskState.Ready());
            task.clearClaim();
            task.note(CorrespondenceEntry.Kind.ABORT, "abort: " + record.cause());
        });
    }

    /** Resets abort history only; leaves the logical state untouched (D1-D4, FR3/UX1 of fix-abort-progress-reset). */
    @Override
    public void recordProgress(TaskRef ref) {
        withLock(() -> {
            TrackedTask task = requireTask(ref);
            task.recordProgress();
            task.note(CorrespondenceEntry.Kind.PROGRESS, "progress recorded");
        });
    }

    @Override
    public void acknowledgeDecision(TaskRef ref, String decisionText) {
        withLock(() -> {
            TrackedTask task = requireTask(ref);
            task.acknowledge();
            task.note(CorrespondenceEntry.Kind.ACK, "acting on decision: " + decisionText);
        });
    }

    /** No read-side fact, but still belongs in the thread (UX4). */
    @Override
    public void postNote(TaskRef ref, String text) {
        withLock(() -> requireTask(ref).note(CorrespondenceEntry.Kind.NOTE, text));
    }

    @Override
    public List<OpenTask> listOpen() {
        return leaseOps.listOpen();
    }

    @Override
    public HeartbeatResult heartbeat(TaskRef ref, String progressPayload) {
        return leaseOps.heartbeat(ref, progressPayload);
    }

    @Override
    public RemoveStaleClaimResult removeStaleClaim(TaskRef ref, ClaimVersion observedVersion) {
        return leaseOps.removeStaleClaim(ref, observedVersion);
    }

    <T> T withLock(Supplier<T> body) {
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    private void withLock(Runnable body) {
        lock.lock();
        try {
            body.run();
        } finally {
            lock.unlock();
        }
    }

    TrackedTask requireTask(TaskRef ref) {
        TrackedTask task = store.get(ref);
        if (task == null) {
            throw new NoSuchTrackedTaskException(ref);
        }
        return task;
    }
}
