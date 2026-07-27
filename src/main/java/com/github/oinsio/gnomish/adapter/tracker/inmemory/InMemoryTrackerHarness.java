package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import com.github.oinsio.gnomish.DoNotMutate;
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
 * Test-support companion to {@link InMemoryTracker} (task 2.6, FR3): human
 * operations (reply, return-to-ready, close, edit) that simulate what a human
 * does in a tracker UI, fixture-seeding hooks that load a task directly into an
 * arbitrary state bypassing the normal claim/park/finish/recordAbort
 * transition rules, and the arming point for {@link InMemoryTracker}'s
 * race-interleaving hook. Kept as a separate class, constructor-injected with
 * the {@link InMemoryTracker} it wraps, because adding these methods directly
 * to {@link InMemoryTracker} would push it past the project's 200-line file
 * cap (`.claude/rules/process-invariants.md`); being in the SAME package, it
 * reaches into {@link InMemoryTracker}'s package-private {@link
 * InMemoryTracker#store}/{@link InMemoryTracker#lock} and {@link
 * TrackedTask}'s package-private mutators exactly as a same-file helper
 * would.
 *
 * <p>Still production code shipping under {@code adapter.tracker.inmemory}
 * (the executable reference for adapter authors, FR3/G2) — not test-only
 * code — even though its purpose is exercised only by tests: {@code
 * TrackerFetchContract}'s {@code seedTask}/{@code seedReply} hooks (task 2.7)
 * delegate to {@link #seed} and {@link #seedReply} here one-for-one.
 *
 * <p>Implements FR3 of add-tracker-port.
 *
 * @param adapter the in-memory tracker this harness seeds and drives
 */
public record InMemoryTrackerHarness(InMemoryTracker adapter) {

    /**
     * The {@link Tracker} under test, for callers that only need the port view
     * (e.g. an {@code arrange()} seam returning {@code Optional<Tracker>}).
     *
     * @return the wrapped adapter, viewed as a {@link Tracker}
     */
    public Tracker tracker() {
        return adapter;
    }

    /**
     * Simulates a human posting a free-text reply comment on {@code ref},
     * visible to a subsequent {@code collectDecisions} per the normal port
     * semantics (i.e. only if posted after the last ack) — FR12.
     *
     * @param ref the task's canonical identity; must already be seeded
     * @param body the reply text; never blank
     */
    public void reply(TaskRef ref, String body) {
        seedReply(adapter, ref, new HumanReply(body, Instant.now()));
    }

    /**
     * Simulates a human moving a parked task back to {@code Ready} — the ONLY
     * way {@code AwaitingHuman -> Ready} happens (tracker-port spec, "Parked
     * task returns only through a human"). Does not touch abort facts or
     * pending replies.
     *
     * @param ref the task's canonical identity; must already be seeded
     */
    public void returnToReady(TaskRef ref) {
        adapter.lock.lock();
        try {
            requireSeeded(ref).state(new TrackerTaskState.Ready());
        } finally {
            adapter.lock.unlock();
        }
    }

    /**
     * Simulates a human closing the task, transitioning it to {@link
     * TrackerTaskState.Gone}.
     *
     * @param ref the task's canonical identity; must already be seeded
     */
    public void close(TaskRef ref) {
        adapter.lock.lock();
        try {
            requireSeeded(ref).state(new TrackerTaskState.Gone());
        } finally {
            adapter.lock.unlock();
        }
    }

    /**
     * Simulates a human editing the issue's title/body in the tracker UI while
     * the task is in flight (Working or parked) — mutating the LIVE snapshot
     * only, never the claim-time snapshot a taken task already froze into its
     * {@code TaskContext}/{@code task.json} (FR11). A subsequent {@code
     * fetchTask} reflects the edit; the frozen copy does not, which is exactly
     * what "snapshot at first claim" guarantees. The id is the issue's identity
     * and cannot change — it stays {@code ref.id()}; only title/body are edited.
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

    /**
     * Loads one fixture task directly into {@code state} with {@code
     * abortFacts}, bypassing every normal transition rule — the seeding hook
     * {@code TrackerFetchContract#seedTask} (task 2.7) delegates to this
     * one-for-one, since the port's ten operations alone cannot express an
     * arbitrary state/abort-history combination in one call (e.g. a {@code
     * Finished} task with three recorded aborts). Replacing an already-seeded
     * ref overwrites it, including any previously seeded replies.
     *
     * @param ref the fixture task's identity
     * @param snapshot the fixture task's frozen id/title/body
     * @param state the fixture task's logical state
     * @param abortFacts the fixture task's abort history
     */
    public void seed(TaskRef ref, TaskSnapshot snapshot, TrackerTaskState state, AbortFacts abortFacts) {
        adapter.lock.lock();
        try {
            TrackedTask task = new TrackedTask(snapshot, state);
            // recordAbort increments by exactly one per call (production semantics), so an
            // arbitrary seeded count is replayed that many times; every call after the first
            // reuses the same timestamp, leaving lastAbortAt equal to abortFacts.lastAbortAt().
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

    // PIT M4 documented exception (build.gradle has the full rationale style): @DoNotMutate
    // because count() > 0 vs. count() >= 0 is a genuine equivalent mutant here, not a real
    // coverage gap — AbortFacts.count() is non-negative by construction (requireNonNegative), so
    // count == 0 is the only value the boundary shift could affect, and the replay loop above is
    // itself bounded by count(), so it iterates zero times either way. Covered by
    // InMemoryTrackerHarnessSpec's "seed does not replay recordAbort when abort count is exactly
    // zero" (the irregular count==0/non-null-timestamp case that would expose any real behavior
    // difference, and does not show one).
    /** Whether {@code abortFacts} records at least one abort to replay in {@link #seed}. */
    @DoNotMutate
    private static boolean hasAborts(AbortFacts abortFacts) {
        return abortFacts.count() > 0;
    }

    /**
     * Injects a pending human reply directly, as if a human had just replied in the tracker.
     * Callable both before and after an ack against the same seeded task, so a caller can seed
     * a reply, ack it, then seed a second reply to verify the stale one never resurfaces
     * (tracker-port spec, "Stale replies never resurface"). The {@code adapter} parameter
     * mirrors {@code TrackerFetchContract}-style seeding hook signatures; it must be this
     * harness's own wrapped instance.
     *
     * @param adapter the tracker adapter this harness wraps; must be {@link #adapter} itself
     * @param ref the fixture task's identity; must already be seeded via {@link #seed}
     * @param reply the pending human reply to seed
     */
    public void seedReply(Tracker adapter, TaskRef ref, HumanReply reply) {
        if (!Objects.equals(adapter, this.adapter)) {
            throw new IllegalArgumentException("seedReply adapter must be this harness's own wrapped instance");
        }
        this.adapter.lock.lock();
        try {
            requireSeeded(ref).addReply(reply);
        } finally {
            this.adapter.lock.unlock();
        }
    }

    /**
     * Arms {@link InMemoryTracker}'s race-interleaving test hook: from this call on, every
     * {@code claim} runs {@code gate} before competing for the store lock — e.g. line every
     * caller up on a shared {@link java.util.concurrent.CyclicBarrier} for a GUARANTEED
     * interleaving, rather than the weaker "throw concurrent threads at it and hope" guarantee a
     * bare virtual-thread race already gets for free. Adapter authors building their own
     * harness's "Race simulation" scenario should expect to need an analogous hook (FR3, G2).
     *
     * @param gate run once per {@code claim} call, before the store lock is
     *     acquired; typically a {@link java.util.concurrent.CyclicBarrier#await()}
     */
    public void armClaimGate(Runnable gate) {
        adapter.claimGate = gate;
    }

    /** Clears a previously armed claim gate, restoring normal {@code claim} behavior. */
    public void disarmClaimGate() {
        adapter.claimGate = null;
    }

    /**
     * Reads back {@code ref}'s correspondence thread, oldest first (FR18, M3, UX4: "the tracker
     * issue thread alone tells the story of a task"). Read-only: for test assertions only.
     *
     * @param ref the task's canonical identity; must already be seeded
     * @return the ordered thread so far; never null, may be empty
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
