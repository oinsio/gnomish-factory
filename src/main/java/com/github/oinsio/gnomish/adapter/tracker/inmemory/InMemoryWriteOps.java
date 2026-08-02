package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import com.github.oinsio.gnomish.app.port.tracker.AbortRecord;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;

/**
 * The coordination writes of {@link InMemoryTracker} — {@code release}, {@code park}, {@code
 * finish}, {@code recordAbort}, {@code recordProgress}, {@code acknowledgeDecision}, {@code
 * postNote} — extracted from that class for file size. Each runs under the tracker's coarse lock via
 * {@link InMemoryTracker#withLock(java.util.function.Supplier)} and appends a {@link
 * CorrespondenceEntry} to the task's thread, except {@code release} (FR18, M3, UX4, D2 of
 * add-tracker-port). State, claim, and thread mutation stay on {@link TrackedTask}.
 */
record InMemoryWriteOps(InMemoryTracker tracker) {

    /** Leaves the logical state untouched (design D2, FR15) but drops any claim marker (FR5). */
    void release(TaskRef ref) {
        runLocked(() -> tracker.requireTask(ref).clearClaim());
    }

    void park(TaskRef ref, ParkReason reason, String report) {
        runLocked(() -> {
            TrackedTask task = tracker.requireTask(ref);
            task.state(new TrackerTaskState.AwaitingHuman(reason));
            task.clearClaim();
            task.report(report);
            task.note(CorrespondenceEntry.Kind.PARK, "parked (" + reason + "): " + report);
        });
    }

    void finish(TaskRef ref, String summary) {
        runLocked(() -> {
            TrackedTask task = tracker.requireTask(ref);
            task.state(new TrackerTaskState.Finished());
            task.clearClaim();
            task.summary(summary);
            task.note(CorrespondenceEntry.Kind.FINISH, summary);
        });
    }

    void recordAbort(TaskRef ref, AbortRecord record) {
        runLocked(() -> {
            TrackedTask task = tracker.requireTask(ref);
            task.recordAbort(record.at());
            task.state(new TrackerTaskState.Ready());
            task.clearClaim();
            task.note(CorrespondenceEntry.Kind.ABORT, "abort: " + record.cause());
        });
    }

    /** Resets abort history only; leaves the logical state untouched (D1-D4, FR3/UX1 of fix-abort-progress-reset). */
    void recordProgress(TaskRef ref) {
        runLocked(() -> {
            TrackedTask task = tracker.requireTask(ref);
            task.recordProgress();
            task.note(CorrespondenceEntry.Kind.PROGRESS, "progress recorded");
        });
    }

    void acknowledgeDecision(TaskRef ref, String decisionText) {
        runLocked(() -> {
            TrackedTask task = tracker.requireTask(ref);
            task.acknowledge();
            task.note(CorrespondenceEntry.Kind.ACK, "acting on decision: " + decisionText);
        });
    }

    /** No read-side fact, but still belongs in the thread (UX4). */
    void postNote(TaskRef ref, String text) {
        runLocked(() -> tracker.requireTask(ref).note(CorrespondenceEntry.Kind.NOTE, text));
    }

    private void runLocked(Runnable body) {
        tracker.withLock(body);
    }
}
