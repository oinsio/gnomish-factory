package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The scheduler's slot ledger (design D1): a {@link Semaphore}-backed capacity primitive for the
 * feed loop's N free slots. The feed calls {@link #acquire()} <em>before</em> attempting a
 * claim, which is what makes "claim attempts in flight never exceed free slots" structural
 * rather than merely checked (FR1, NFR-R1) — a permit is spent before any tracker call is made.
 * Once a claim succeeds, the feed calls {@link #assign(TaskRef)} to tie the already-acquired
 * permit to that task; {@link #assign(TaskRef)} rejects a task already tied to a slot of this
 * instance, which is the in-process guarantee behind FR1's "never assign one task to two slots
 * of the same instance". A slot calls {@link #release(TaskRef)} on its terminal result, which
 * frees the permit and unblocks anything parked in {@link #acquire()} — the local slot-freed
 * event design D1 describes for the feed's Full state (no additional signal is needed: a {@link
 * Semaphore} already wakes every thread blocked in {@code acquire()} on {@link
 * Semaphore#release()}). If a claim attempt fails after a permit was acquired, the feed calls
 * {@link #abandon()} to return that unassigned permit to the pool.
 *
 * <p>Holds only the ledger — no claiming, no slot bodies, no automaton states; the feed
 * automaton (task 4.2) drives this primitive through its Filling/Idle/Full transitions.
 *
 * <p>Implements FR1, D1, NFR-R1 of add-factory-serve. Implements FR11, D9 of add-factory-serve
 * (the SIGTERM shutdown sequence's bounded {@link #awaitDrained(Duration)} and {@link
 * #occupiedRefs()}). Implements FR6, FR11 of add-serve-observability (each occupied slot's {@code
 * since}, exposed via {@link #occupiedEntries()}). Implements FR1 of add-serve-observability
 * (design D4): {@link #assign(TaskRef)} and {@link #release(TaskRef)} — the calls that change
 * occupancy — wake the injected {@link DirtyNotifier} for an immediate snapshot write.
 */
public final class SlotLedger {

    private final Semaphore permits;
    private final Map<TaskRef, Instant> occupied = new ConcurrentHashMap<>();
    private final int totalSlots;
    private final Clock clock;
    private final DirtyNotifier dirtyNotifier;

    /** Equivalent to {@link #SlotLedger(int, Clock)} with the production system clock. */
    public SlotLedger(int slots) {
        this(slots, new SystemClock());
    }

    /**
     * Equivalent to {@link #SlotLedger(int, Clock, DirtyNotifier)} with a no-op notifier — every
     * caller predating the observability writer (task 5.x wires the real one).
     */
    public SlotLedger(int slots, Clock clock) {
        this(slots, clock, DirtyNotifier.NOOP);
    }

    /**
     * @param slots the instance's slot count N; must be positive
     * @param clock the time source {@link #assign(TaskRef)} reads to capture each slot's {@code
     *     since} (FR6, feeds FR11's {@code startedAt}); a fixed/controllable clock in tests
     * @param dirtyNotifier woken by {@link #assign(TaskRef)} and {@link #release(TaskRef)} (FR1 of
     *     add-serve-observability, design D4); {@link DirtyNotifier#NOOP} absent a writer to wake
     */
    public SlotLedger(int slots, Clock clock, DirtyNotifier dirtyNotifier) {
        if (slots <= 0) {
            throw new IllegalArgumentException("SlotLedger slots must be positive");
        }
        this.permits = new Semaphore(slots);
        this.totalSlots = slots;
        this.clock = clock;
        this.dirtyNotifier = dirtyNotifier;
    }

    /**
     * Acquires one free-slot permit, blocking until one is available. The feed calls this before
     * every claim attempt (design D1), so claim attempts in flight can never exceed free slots —
     * a permit is spent up front, not after the fact.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void acquire() throws InterruptedException {
        permits.acquire();
    }

    /**
     * Ties the permit most recently acquired by this thread to {@code ref}, marking that task as
     * occupying a slot of this instance. Called once a claim succeeds. Also captures the current
     * instant from the injected {@link Clock} as this slot's {@code since} (FR6, feeds FR11's
     * {@code startedAt}), read back via {@link #occupiedEntries()}.
     *
     * @throws IllegalStateException if {@code ref} already occupies a slot of this instance (the
     *     FR1 same-instance double-assignment guard)
     */
    public void assign(TaskRef ref) {
        if (occupied.putIfAbsent(ref, clock.now()) != null) {
            throw new IllegalStateException("task " + ref.id() + " already occupies a slot");
        }
        DirtyNotifier.markDirtySafely(dirtyNotifier, "SlotLedger.assign");
    }

    /**
     * Releases the slot occupied by {@code ref} at its terminal result: clears the occupancy and
     * returns the permit to the pool, waking anything waiting in {@link #acquire()} — the local
     * slot-freed event of design D1.
     *
     * @param ref the task whose slot has reached a terminal result
     * @throws IllegalStateException if {@code ref} does not currently occupy a slot of this
     *     instance
     */
    public void release(TaskRef ref) {
        if (occupied.remove(ref) == null) {
            throw new IllegalStateException("task " + ref.id() + " does not occupy a slot");
        }
        permits.release();
        DirtyNotifier.markDirtySafely(dirtyNotifier, "SlotLedger.release");
    }

    /**
     * Returns an acquired-but-never-assigned permit to the pool — the feed's path when a claim
     * attempt fails after {@link #acquire()} spent a permit but before any task occupied it.
     */
    public void abandon() {
        permits.release();
    }

    /** The number of permits currently free, for tests and observability. */
    public int freeSlots() {
        return permits.availablePermits();
    }

    /**
     * Blocks until every slot is free — drain mode's wait barrier (FR10). Once the feed has
     * decided to stop calling {@link #acquire()} for good, the only permits still outstanding
     * belong to slots running their tasks to a terminal result; acquiring all N of them can
     * therefore only be unblocked by every one of those slots calling {@link #release} (or an
     * abandoned in-flight attempt calling {@link #abandon()}). The permits are handed straight
     * back once acquired, so this is a wait, not a permanent acquisition — {@link #freeSlots()}
     * reads N again immediately afterwards.
     *
     * <p>Implements FR10 of add-factory-serve.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void awaitDrained() throws InterruptedException {
        permits.acquire(totalSlots);
        permits.release(totalSlots);
    }

    /**
     * The bounded sibling of {@link #awaitDrained()} — the SIGTERM grace-window wait (FR11,
     * design D9): blocks for at most {@code timeout} for every slot to become free, exactly like
     * {@link #awaitDrained()} otherwise. Unlike the unbounded method, a grace window that expires
     * with some slots still occupied is not an error: those tasks' rounds simply outlived the
     * window, are left alone, and the caller proceeds regardless.
     *
     * @return {@code true} if every slot became free within {@code timeout}; {@code false} if the
     *     window expired with at least one slot still occupied
     */
    public boolean awaitDrained(Duration timeout) throws InterruptedException {
        boolean allFree = permits.tryAcquire(totalSlots, timeout.toNanos(), TimeUnit.NANOSECONDS);
        if (allFree) {
            permits.release(totalSlots);
        }
        return allFree;
    }

    /**
     * A snapshot of the task refs currently occupying a slot of this instance — the SIGTERM
     * shutdown sequence's source of "which claims to flag as gracefully stopped" (FR11, design
     * D9): every ref {@link #assign} has tied to a permit and {@link #release} has not yet freed.
     * A snapshot, not a live view.
     */
    public Set<TaskRef> occupiedRefs() {
        return Set.copyOf(occupied.keySet());
    }

    /**
     * A snapshot of every occupied slot as {@code {taskId, since}} pairs — the snapshot's {@code
     * slots} section (FR6) and the ledger's {@code taskOutcome.startedAt} (FR11) both read this:
     * each entry's {@link OccupiedSlot#since()} is the instant {@link #assign(TaskRef)} tied that
     * ref to its slot. A snapshot, not a live view, exactly like {@link #occupiedRefs()}.
     *
     * <p>Implements FR6, FR11 of add-serve-observability.
     *
     * @return an immutable copy of the currently-occupied slots; possibly empty; never null
     */
    public Set<OccupiedSlot> occupiedEntries() {
        return occupied.entrySet().stream()
                .map(entry -> new OccupiedSlot(entry.getKey(), entry.getValue()))
                .collect(Collectors.toUnmodifiableSet());
    }
}
