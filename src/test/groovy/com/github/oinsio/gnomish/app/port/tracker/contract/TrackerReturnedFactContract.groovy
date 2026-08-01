package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

import java.time.Instant

/**
 * The {@code listReady} "returned" fact properties of the {@link Tracker}
 * port contract suite (tracker-port spec, "Ready listing carries the
 * returned fact" / "Contract suite covers the returned fact"; task 1.2,
 * FR6, FR7, NFR-R1 of add-factory-serve). Extends {@link TrackerReapContract}
 * — the most-derived link in the lease chain — to reuse every seam it
 * accumulates ({@code arrange}, {@code seedTask}, {@code seedReply}, {@code
 * seedWorkingWithClaim}) rather than duplicating them; a concrete adapter
 * subclass instantiates THIS class (not {@link TrackerReapContract} or any
 * earlier link directly) to run the full suite, per M1 — the same suite
 * against every adapter. Kept in its own file because the combined property
 * set of the whole chain would exceed the project's per-file line cap.
 *
 * <p>{@link #returnToReady} is the one new seam this file adds: the port
 * itself exposes no "un-park" operation (a human moves {@code AwaitingHuman}
 * back to {@code Ready} directly in the tracker UI, never the factory, per
 * {@link Tracker#park}'s own contract), so a concrete adapter spec supplies a
 * test-only simulation of that human action — mirroring {@code
 * InMemoryTrackerHarness#returnToReady}, which already exists for this exact
 * purpose. The seam MUST touch only the logical state, never the recorded
 * correspondence history (park report, claim/holder-transition markers), so
 * the returned-fact derivation an adapter builds on top of that history in
 * tasks 1.3/1.4 has something real to observe.
 *
 * <p>The "park-and-return" and "stale-claim removal" properties below are
 * EXPECTED to fail until tasks 1.3 (in-memory) and 1.4 (GitHub) replace the
 * {@code returned = false} placeholder every {@code ReadyTask} construction
 * site currently carries with a real derivation from adapter history — this
 * is intentional TDD red, not a suite defect. The "never-claimed" and
 * "listOpen size" properties pass immediately: the former's expected value is
 * {@code false}, which the placeholder already satisfies; the latter asserts
 * behavior {@code listOpen} already had before this task.
 *
 * <p>Implements FR6, FR7, NFR-R1 of add-factory-serve.
 */
abstract class TrackerReturnedFactContract extends TrackerReapContract {

    /**
     * Simulates a human moving a parked task ({@code AwaitingHuman}) back to
     * {@code Ready} directly in the tracker UI — the only way that transition
     * happens (design: {@link Tracker#park} is factory-initiated only; exits
     * are human actions). MUST leave the task's recorded correspondence
     * history (park report, prior claim markers) intact — only the logical
     * state moves — so a "returned" derivation built over that history has
     * something to observe.
     *
     * @param adapter the tracker adapter arranged by {@link #arrange}
     * @param ref the fixture task's identity; must already be seeded and
     *     currently {@code AwaitingHuman}
     */
    protected abstract void returnToReady(Tracker adapter, TaskRef ref)

    // FR6, FR7: a task that was seeded straight into Ready, never claimed or
    //     parked, carries no "previously worked and given back" history — its
    //     returned fact is false ("Fresh task is not returned")
    def "listReady reports the returned fact as false for a never-claimed task"() {
        given: 'a tracker seeded with one Ready task that was never claimed or parked'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'returned-fact never-claimed fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:returned-never-claimed')
        seedTask(adapter, ref, new TrackerTaskState.Ready(), AbortFacts.none())

        when: 'listReady is called'
        List<ReadyTask> result = adapter.listReady(10)

        then: 'the entry reports returned = false'
        result.find { it.ref() == ref }.returned() == false
    }

    // FR6, FR7: a task claimed, parked with an escalation report, then moved back
    //     to Ready by a human carries a park-report history — its returned fact is
    //     true ("Park round-trip sets the fact"); EXPECTED RED until task 1.3/1.4
    //     replace the returned = false placeholder with a real derivation
    def "listReady reports the returned fact as true after a park-and-return round-trip"() {
        given: 'a task claimed, then parked with an escalation report'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'returned-fact park round-trip fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:returned-park-round-trip')
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        adapter.park(ref, ParkReason.ESCALATION, 'stuck: needs a human decision')

        when: 'a human moves the parked task back to Ready, and listReady is called'
        returnToReady(adapter, ref)
        List<ReadyTask> result = adapter.listReady(10)

        then: 'the entry reports returned = true — the park report is recorded history'
        result.find { it.ref() == ref }.returned() == true
    }

    // FR6, FR7: a task whose stale claim was reaped and returned to Ready carries a
    //     holder-transition-marker history — its returned fact is true ("Reaped task
    //     is returned"); EXPECTED RED until task 1.3/1.4 replace the placeholder
    def "listReady reports the returned fact as true after a stale-claim removal"() {
        given: 'a Working task holding a live claim'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'returned-fact stale-claim fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:returned-stale-claim')
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        def observed = adapter.listOpen().find { it.ref() == ref }.claimVersion()

        when: 'a reaper removes the stale claim, returning the task to Ready, and listReady is called'
        adapter.removeStaleClaim(ref, observed)
        List<ReadyTask> result = adapter.listReady(10)

        then: 'the entry reports returned = true — the holder-transition marker is recorded history'
        result.find { it.ref() == ref }.returned() == true
    }

    // FR6, FR7: a task claimed then returned to Ready via an infrastructure abort
    //     (backoff protocol, not a human return or reaper takeover) carries a
    //     claim/abort history with neither a park report nor a stale-claim-removal
    //     marker — its returned fact stays false ("Abort-backoff return is not the
    //     returned fact"), distinguishing it from the park/stale-claim round-trips above
    def "listReady reports the returned fact as false for a task returned via abort backoff"() {
        given: 'a task claimed, then returned to Ready by an infrastructure abort'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'returned-fact abort-backoff fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:returned-abort-backoff')
        seedWorkingWithClaim(adapter, ref, 'instance-a')

        when: 'the abort protocol records an abort and returns the task to Ready, and listReady is called'
        adapter.recordAbort(ref, new AbortRecord('infra hiccup', 'instance-a', Instant.now()))
        List<ReadyTask> result = adapter.listReady(10)

        then: 'the entry reports returned = false — an abort marker is not a returned marker'
        result.find { it.ref() == ref }.returned() == false
    }

    // FR6: listOpen's size is directly usable as the WIP policy's open-front count —
    //     no separate counting operation exists on the port, so the count the policy
    //     consumes IS the size of this listing
    def "listOpen's size equals the number of open tasks, directly usable as the open-front count"() {
        given: 'a tracker seeded with two open tasks (Working, AwaitingHuman) and two non-open tasks'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'open-front count fixture')
        def adapter = tracker.get()
        seedWorkingWithClaim(adapter, new TaskRef('fixture:open-front-working'), 'instance-a')
        seedTask(adapter, new TaskRef('fixture:open-front-awaiting'),
                new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION), AbortFacts.none())
        seedTask(adapter, new TaskRef('fixture:open-front-ready'), new TrackerTaskState.Ready(), AbortFacts.none())
        seedTask(adapter, new TaskRef('fixture:open-front-finished'), new TrackerTaskState.Finished(), AbortFacts.none())

        when: 'listOpen is called'
        List<OpenTask> result = adapter.listOpen()

        then: 'its size is exactly the two open tasks — the open-front count the WIP policy consumes'
        result.size() == 2
    }
}
