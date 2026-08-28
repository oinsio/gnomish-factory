package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.app.port.tracker.HumanReply;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause;
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Package-private mutable holder for one task's state inside {@link
 * InMemoryTracker}'s store: the live {@link TaskSnapshot} (id/title/body as the
 * issue currently reads — a human editing the issue mutates it via {@link
 * InMemoryTrackerHarness#edit}; FR11's claim-time freeze lives in the taken
 * task's {@code task.json}, not here), the current
 * {@link TrackerTaskState}, abort history, pending human replies, the
 * decision-ack boundary, the last report/summary text a park/finish call
 * recorded, and the ordered {@link CorrespondenceEntry} thread narrating every
 * coordination write (FR18, M3, UX4). Not part of the {@code Tracker} port's
 * public shape — a plain data bag the adapter mutates under its own lock (see
 * {@link InMemoryTracker}).
 *
 * <p>Deliberately package-private and mutable rather than an immutable record
 * swapped in the map on every write: task 2.6 (human-simulation operations —
 * reply, return-to-ready, close) and its race-interleaving test hooks extend
 * this same store and need a natural place to append a reply or flip a state
 * without reconstructing the whole holder.
 *
 * <p>Implements FR1, FR2, FR3 of add-tracker-port.
 */
final class TrackedTask {

    private TaskSnapshot snapshot;
    private TrackerTaskState state;
    private int abortCount;
    private int recoveryCount;
    private @Nullable Instant lastAbortAt;
    private final List<HumanReply> pendingReplies = new ArrayList<>();
    private @Nullable Instant lastAckAt;
    private @Nullable String lastReport;
    private @Nullable String lastSummary;
    private @Nullable ClaimMarker claimMarker;
    private @Nullable String lastClaimHolder;
    private @Nullable ParkReason lastParkReason;
    private final List<CorrespondenceEntry> thread = new ArrayList<>();

    TrackedTask(TaskSnapshot snapshot, TrackerTaskState state) {
        this.snapshot = snapshot;
        this.state = state;
    }

    TaskSnapshot snapshot() {
        return snapshot;
    }

    /**
     * Overwrites the live snapshot when a human edits the issue in the tracker
     * (simulated by {@link InMemoryTrackerHarness#edit}). Only the live
     * tracker-side record changes; a taken task's claim-time snapshot, already
     * copied into {@code task.json}, is a separate frozen value (FR11).
     */
    void snapshot(TaskSnapshot newSnapshot) {
        this.snapshot = newSnapshot;
    }

    TrackerTaskState state() {
        return state;
    }

    void state(TrackerTaskState newState) {
        this.state = newState;
    }

    /** The live claim marker, or {@code null} when the task holds no claim (FR5 of add-claim-heartbeat). */
    @Nullable
    ClaimMarker claimMarker() {
        return claimMarker;
    }

    /** Establishes a claim marker whose version {@code listOpen} then reports (FR5 of add-claim-heartbeat). */
    void establishClaim(ClaimMarker marker) {
        this.claimMarker = marker;
        this.lastClaimHolder = marker.holder();
    }

    /**
     * The instance named by the most recent claim marker this task ever carried, kept after the
     * marker itself is gone so a dead footprint can still name its last-known holder (FR19 of
     * harden-task-branch-contract).
     */
    @Nullable
    String lastClaimHolder() {
        return lastClaimHolder;
    }

    /** Remembers the reason of the latest park, so a lagging park flip can be completed with it. */
    void parkReason(ParkReason reason) {
        this.lastParkReason = reason;
    }

    /** The latest recorded park reason, or {@code null} when this task was never parked. */
    @Nullable
    ParkReason parkReason() {
        return lastParkReason;
    }

    /** Clears the marker when the task leaves {@code Working} — park/finish/recordAbort/release (FR5). */
    void clearClaim() {
        this.claimMarker = null;
    }

    AbortFacts abortFacts() {
        return new AbortFacts(abortCount, lastAbortAt, recoveryCount);
    }

    /**
     * Spends one attempt of the unified recovery accounting (FR14 of harden-task-branch-contract):
     * the total always advances, and {@code category} decides which share of it grows.
     */
    void recordAbort(Instant at, RecoveryCause category) {
        abortCount++;
        if (category == RecoveryCause.RECOVERY_FAILURE) {
            recoveryCount++;
        }
        lastAbortAt = at;
    }

    /**
     * Resets abort history on forward progress (FR3, D4): a task that resumes work after one or
     * more aborts is no longer "stuck", so the abort streak is cleared. The {@code PROGRESS}
     * thread entry narrating this reset is appended by {@link InMemoryTracker}, not here (mirrors
     * {@link #recordAbort}, which likewise only mutates fields).
     */
    void recordProgress() {
        abortCount = 0;
        recoveryCount = 0;
        lastAbortAt = null;
    }

    void addReply(HumanReply reply) {
        pendingReplies.add(reply);
    }

    /** Replies posted after {@code lastAckAt}, in posting order (FR12). */
    List<HumanReply> decisionsSinceAck() {
        if (lastAckAt == null) {
            return List.copyOf(pendingReplies);
        }
        return pendingReplies.stream()
                .filter(reply -> reply.postedAt().isAfter(lastAckAt))
                .toList();
    }

    /**
     * Anchors the ack boundary to the {@code postedAt} of the most recent
     * currently-pending reply, not wall-clock "now": {@code postedAt} values are
     * caller-supplied (fixture seeding, or a tracker adapter's own comment
     * timestamps) and are not guaranteed to be close to real time, so anchoring
     * to {@link Instant#now()} could leave a just-seeded reply "after" the ack
     * boundary and wrongly still pending (FR12). A no-op when there are no
     * pending replies to anchor to.
     */
    void acknowledge() {
        pendingReplies.stream()
                .map(HumanReply::postedAt)
                .max(Instant::compareTo)
                .ifPresent(latest -> this.lastAckAt = latest);
    }

    void report(String report) {
        this.lastReport = report;
    }

    @Nullable
    String report() {
        return lastReport;
    }

    void summary(String summary) {
        this.lastSummary = summary;
    }

    @Nullable
    String summary() {
        return lastSummary;
    }

    /**
     * Appends one entry to this task's correspondence thread (UX4, M3): called by {@link
     * InMemoryTracker}'s coordination writes as a side effect, never by the port's read
     * operations.
     */
    void note(CorrespondenceEntry.Kind kind, String text) {
        thread.add(new CorrespondenceEntry(kind, text));
    }

    /** The full correspondence thread so far, oldest first; never null, may be empty. */
    List<CorrespondenceEntry> thread() {
        return List.copyOf(thread);
    }
}
