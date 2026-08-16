package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.app.port.tracker.HumanReply;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Test-support companion to {@link InMemoryTracker} (FR3): human operations (reply, return-to-ready,
 * close, edit) that simulate the tracker UI, plus the arming points for the adapter's
 * race-interleaving hooks; fixture seeding is delegated to {@link InMemoryTrackerSeeding}. Kept as a
 * separate class (constructor-injected with the {@link InMemoryTracker} it wraps) because adding these
 * methods to {@link InMemoryTracker} would push it past the 200-line cap; being in the SAME package it
 * reaches into that class's package-private {@code store}/{@code lock} and {@link TrackedTask}'s
 * mutators.
 *
 * <p>Still production code shipping under {@code adapter.tracker.inmemory} (the executable reference
 * for adapter authors, FR3/G2), even though only tests exercise it: {@code TrackerFetchContract}'s
 * {@code seedTask}/{@code seedReply} hooks delegate to {@link #seed}/{@link #seedReply}.
 *
 * <p>Implements FR3 of add-tracker-port.
 *
 * @param adapter the in-memory tracker this harness seeds and drives
 */
public record InMemoryTrackerHarness(InMemoryTracker adapter) {

    /** The {@link Tracker} under test, for callers that only need the port view. */
    public Tracker tracker() {
        return adapter;
    }

    /**
     * Simulates a human posting a free-text reply comment on {@code ref}, visible to a subsequent
     * {@code collectDecisions} per the normal port semantics (only if posted after the last ack, FR12).
     */
    public void reply(TaskRef ref, String body) {
        seedReply(adapter, ref, new HumanReply(body, Instant.now()));
    }

    /**
     * Simulates a human moving a parked task back to {@code Ready} — the ONLY way {@code AwaitingHuman
     * -> Ready} happens. Does not touch abort facts or pending replies.
     */
    public void returnToReady(TaskRef ref) {
        moveToReady(ref);
    }

    /**
     * Simulates a human moving a finished task back to {@code Ready} — the ONLY way {@code Finished
     * -> Ready} happens (the factory itself never resumes a finished task). Does not touch the
     * recorded correspondence history (the finish report stays intact).
     */
    public void reopenFinished(TaskRef ref) {
        moveToReady(ref);
    }

    /**
     * The shared {@code * -> Ready} move behind {@link #returnToReady} and {@link #reopenFinished}:
     * both simulate a human relabeling the issue back to {@code Ready} in the tracker UI, touching
     * only the logical state and never the recorded correspondence history. The two public seams stay
     * distinct because they model two different human actions from two different terminal states
     * (park-return vs finish-reopen), but the mechanics are identical.
     */
    private void moveToReady(TaskRef ref) {
        adapter.lock.lock();
        try {
            requireSeeded(ref).state(new TrackerTaskState.Ready());
        } finally {
            adapter.lock.unlock();
        }
    }

    /** Simulates a human closing the task, transitioning it to {@link TrackerTaskState.Gone}. */
    public void close(TaskRef ref) {
        adapter.lock.lock();
        try {
            requireSeeded(ref).state(new TrackerTaskState.Gone());
        } finally {
            adapter.lock.unlock();
        }
    }

    /**
     * Simulates a human editing the issue's title/body in the tracker UI while the task is in flight —
     * mutating the LIVE snapshot only, never the claim-time snapshot a taken task already froze into
     * its {@code task.json} (FR11). The id is the issue's identity and cannot change.
     *
     * @param ref the task's canonical identity; must already be seeded
     * @param title the issue's new title; never blank
     * @param body the issue's new body/description; never null, may be empty
     */
    public void edit(TaskRef ref, String title, String body) {
        adapter.lock.lock();
        try {
            requireSeeded(ref).snapshot(new TaskSnapshot(ref.id(), title, body));
        } finally {
            adapter.lock.unlock();
        }
    }

    /** Loads one fixture task directly into {@code state} with {@code abortFacts} ({@link InMemoryTrackerSeeding#seed}). */
    public void seed(TaskRef ref, TaskSnapshot snapshot, TrackerTaskState state, AbortFacts abortFacts) {
        InMemoryTrackerSeeding.seed(adapter, ref, snapshot, state, abortFacts);
    }

    /**
     * Injects a pending human reply directly. Callable both before and after an ack against the same
     * seeded task (tracker-port spec, "Stale replies never resurface"). The {@code adapter} parameter
     * mirrors {@code TrackerFetchContract}-style hook signatures; it must be this harness's own wrapped
     * instance.
     */
    public void seedReply(Tracker adapter, TaskRef ref, HumanReply reply) {
        if (!Objects.equals(adapter, this.adapter)) {
            throw new IllegalArgumentException("seedReply adapter must be this harness's own wrapped instance");
        }
        InMemoryTrackerSeeding.seedReply(this.adapter, ref, reply);
    }

    /**
     * Seeds one fixture task at {@code Working(holder)} WITH a live claim marker, so {@code listOpen}
     * resolves a non-null {@code ClaimVersion} for it (FR5). Mirrors {@link #seedReply}'s "must be this
     * harness's own instance" guard.
     */
    public void seedWorkingWithClaim(Tracker adapter, TaskRef ref, String holder) {
        if (!Objects.equals(adapter, this.adapter)) {
            throw new IllegalArgumentException(
                    "seedWorkingWithClaim adapter must be this harness's own wrapped instance");
        }
        InMemoryTrackerSeeding.seedWorkingWithClaim(this.adapter, ref, holder);
    }

    /** Arms {@link InMemoryTracker}'s claim race-interleaving hook: every {@code claim} runs {@code gate} before the store lock (FR3, G2). */
    public void armClaimGate(Runnable gate) {
        adapter.claimGate = gate;
    }

    /** Clears a previously armed claim gate, restoring normal {@code claim} behavior. */
    public void disarmClaimGate() {
        adapter.claimGate = null;
    }

    /** Arms the stale-claim-removal interleaving hook: every {@code removeStaleClaim} runs {@code gate} before the store lock (FR5). */
    public void armRemoveStaleClaimGate(Runnable gate) {
        adapter.leaseOps.removeStaleClaimGate = gate;
    }

    /** Clears a previously armed removal gate, restoring normal {@code removeStaleClaim} behavior. */
    public void disarmRemoveStaleClaimGate() {
        adapter.leaseOps.removeStaleClaimGate = null;
    }

    /**
     * Reads back {@code ref}'s correspondence thread, oldest first (FR18, M3, UX4). Read-only: for test
     * assertions only.
     */
    public List<CorrespondenceEntry> thread(TaskRef ref) {
        adapter.lock.lock();
        try {
            return requireSeeded(ref).thread();
        } finally {
            adapter.lock.unlock();
        }
    }

    private TrackedTask requireSeeded(TaskRef ref) {
        TrackedTask task = adapter.store.get(ref);
        if (task == null) {
            throw new NoSuchTrackedTaskException(ref);
        }
        return task;
    }
}
