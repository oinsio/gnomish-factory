package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord;
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.HumanReply;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;

/**
 * In-memory reference implementation of the {@link Tracker} port (design D15):
 * the executable example for adapter authors (FR3, G2). Tasks live in a single
 * {@link LinkedHashMap} keyed by {@link TaskRef}, preserving insertion order as
 * the adapter's queue order for {@link #listReady(int)}; every operation runs
 * under one coarse {@link ReentrantLock} guarding the whole store — not
 * per-task locking, since critical sections are microseconds long and {@link
 * #claim(TaskRef, String)}'s check/decide/mutate sequence must be observably
 * atomic (NFR-R1). Ships with no config subsection (FR3): plain Java
 * construction. {@link InMemoryTrackerHarness} adds human-simulation and
 * fixture-seeding hooks, reaching into this class's package-private {@link
 * #store}/{@link #lock}; this class keeps only {@link #claimGate}.
 *
 * <p>Every coordination write also appends a {@link CorrespondenceEntry} to
 * the task's thread (FR18, M3, UX4), read back via {@link
 * InMemoryTrackerHarness#thread} — {@code release} appends nothing (design
 * D2). Implements FR1, FR2, FR3, FR18, M3, UX4 of add-tracker-port.
 */
public class InMemoryTracker implements Tracker {

    /** The task store, in insertion (adapter queue) order; guarded by {@link #lock}. */
    final Map<TaskRef, TrackedTask> store = new LinkedHashMap<>();

    /** Guards every read/write of {@link #store} and the mutable fields inside each {@link TrackedTask}. */
    final ReentrantLock lock = new ReentrantLock();

    /**
     * Race-interleaving test hook (FR3): when non-null, {@link #claim(TaskRef, String)} runs
     * this before acquiring {@link #lock} (see {@link InMemoryTrackerHarness#armClaimGate}).
     */
    @Nullable
    Runnable claimGate;

    @Override
    public List<ReadyTask> listReady(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("listReady limit must be positive, was " + limit);
        }
        lock.lock();
        try {
            return store.entrySet().stream()
                    .filter(entry -> entry.getValue().state() instanceof TrackerTaskState.Ready)
                    .limit(limit)
                    .map(entry -> new ReadyTask(entry.getKey(), entry.getValue().abortFacts()))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public TrackerTask fetchTask(TaskRef ref) {
        lock.lock();
        try {
            TrackedTask task = store.get(ref);
            if (task == null) {
                return new TrackerTask(ref, goneSnapshot(ref), new TrackerTaskState.Gone(), AbortFacts.none());
            }
            return new TrackerTask(ref, task.snapshot(), task.state(), task.abortFacts());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<HumanReply> collectDecisions(TaskRef ref) {
        lock.lock();
        try {
            TrackedTask task = requireTask(ref);
            return task.decisionsSinceAck();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ClaimResult claim(TaskRef ref, String instanceId) {
        Runnable gate = claimGate;
        if (gate != null) {
            gate.run();
        }
        lock.lock();
        try {
            TrackedTask task = requireTask(ref);
            if (task.state() instanceof TrackerTaskState.Working(String holder)) {
                return new ClaimResult.Held(holder);
            }
            task.state(new TrackerTaskState.Working(instanceId));
            task.note(CorrespondenceEntry.Kind.CLAIM, "claimed by " + instanceId);
            return new ClaimResult.Acquired();
        } finally {
            lock.unlock();
        }
    }

    /** Leaves the logical state untouched (design D2, FR15). */
    @Override
    public void release(TaskRef ref) {
        lock.lock();
        try {
            requireTask(ref);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void park(TaskRef ref, ParkReason reason, String report) {
        lock.lock();
        try {
            TrackedTask task = requireTask(ref);
            task.state(new TrackerTaskState.AwaitingHuman(reason));
            task.report(report);
            task.note(CorrespondenceEntry.Kind.PARK, "parked (" + reason + "): " + report);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void finish(TaskRef ref, String summary) {
        lock.lock();
        try {
            TrackedTask task = requireTask(ref);
            task.state(new TrackerTaskState.Finished());
            task.summary(summary);
            task.note(CorrespondenceEntry.Kind.FINISH, summary);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void recordAbort(TaskRef ref, AbortRecord record) {
        lock.lock();
        try {
            TrackedTask task = requireTask(ref);
            task.recordAbort(record.at());
            task.state(new TrackerTaskState.Ready());
            task.note(CorrespondenceEntry.Kind.ABORT, "abort: " + record.cause());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void acknowledgeDecision(TaskRef ref, String decisionText) {
        lock.lock();
        try {
            TrackedTask task = requireTask(ref);
            task.acknowledge();
            task.note(CorrespondenceEntry.Kind.ACK, "acting on decision: " + decisionText);
        } finally {
            lock.unlock();
        }
    }

    /** No read-side fact, but still belongs in the thread (UX4). */
    @Override
    public void postNote(TaskRef ref, String text) {
        lock.lock();
        try {
            TrackedTask task = requireTask(ref);
            task.note(CorrespondenceEntry.Kind.NOTE, text);
        } finally {
            lock.unlock();
        }
    }

    private TrackedTask requireTask(TaskRef ref) {
        TrackedTask task = store.get(ref);
        if (task == null) {
            throw new NoSuchTrackedTaskException(ref);
        }
        return task;
    }

    private static TaskSnapshot goneSnapshot(TaskRef ref) {
        return new TaskSnapshot(ref.id(), ref.id(), "");
    }
}
