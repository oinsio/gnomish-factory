package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.app.port.tracker.HumanReply;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.time.Instant;

/**
 * Fixture-seeding for {@link InMemoryTracker}, extracted from {@link InMemoryTrackerHarness} for file
 * size. Loads tasks directly into an arbitrary state, bypassing the normal claim/park/finish/abort
 * transition rules — combinations the port's operations alone cannot express in one call (FR3, G2).
 * Operates over the tracker's package-private {@code store}/{@code lock}/{@code claimClock}.
 */
final class InMemoryTrackerSeeding {

    private InMemoryTrackerSeeding() {}

    /**
     * Loads one fixture task directly into {@code state} with {@code abortFacts}, bypassing every
     * transition rule (the {@code TrackerFetchContract#seedTask} hook delegates here). Replacing an
     * already-seeded ref overwrites it, including any previously seeded replies.
     */
    static void seed(
            InMemoryTracker adapter,
            TaskRef ref,
            TaskSnapshot snapshot,
            TrackerTaskState state,
            AbortFacts abortFacts) {
        adapter.lock.lock();
        try {
            TrackedTask task = new TrackedTask(snapshot, state);
            // recordAbort increments by exactly one per call (production semantics), so an arbitrary
            // seeded count is replayed that many times; every call after the first reuses the same
            // timestamp, leaving lastAbortAt equal to abortFacts.lastAbortAt().
            Instant lastAbortAt = abortFacts.lastAbortAt();
            if (hasAborts(abortFacts) && lastAbortAt != null) {
                for (int i = 0; i < abortFacts.count(); i++) {
                    task.recordAbort(lastAbortAt);
                }
            }
            adapter.store.put(ref, task);
        } finally {
            adapter.lock.unlock();
        }
    }

    /** Injects a pending human reply directly, as if a human had just replied in the tracker (FR12). */
    static void seedReply(InMemoryTracker adapter, TaskRef ref, HumanReply reply) {
        adapter.lock.lock();
        try {
            requireSeeded(adapter, ref).addReply(reply);
        } finally {
            adapter.lock.unlock();
        }
    }

    /**
     * Seeds one fixture task at {@code Working(holder)} WITH a live claim marker, so {@code listOpen}
     * resolves a non-null {@code ClaimVersion} for it — the {@code seedWorkingWithClaim} contract seam
     * the lease suite (FR5) needs. Mints the marker through the adapter's own {@code ClaimClock}.
     */
    static void seedWorkingWithClaim(InMemoryTracker adapter, TaskRef ref, String holder) {
        adapter.lock.lock();
        try {
            TrackedTask task = new TrackedTask(
                    new TaskSnapshot(ref.id(), "fixture title", "fixture body"), new TrackerTaskState.Working(holder));
            task.establishClaim(adapter.claimClock.mint(holder));
            adapter.store.put(ref, task);
        } finally {
            adapter.lock.unlock();
        }
    }

    // PIT M4 documented exception: @DoNotMutate because count() > 0 vs. count() >= 0 is a genuine
    // equivalent mutant here, not a coverage gap — AbortFacts.count() is non-negative by construction
    // (requireNonNegative), so count == 0 is the only value the boundary shift could affect, and the
    // replay loop above is itself bounded by count(), so it iterates zero times either way. Covered by
    // InMemoryTrackerHarnessSpec's "seed does not replay recordAbort when abort count is exactly zero".
    /** Whether {@code abortFacts} records at least one abort to replay in {@link #seed}. */
    @DoNotMutate
    private static boolean hasAborts(AbortFacts abortFacts) {
        return abortFacts.count() > 0;
    }

    private static TrackedTask requireSeeded(InMemoryTracker adapter, TaskRef ref) {
        TrackedTask task = adapter.store.get(ref);
        if (task == null) {
            throw new NoSuchTrackedTaskException(ref);
        }
        return task;
    }
}
