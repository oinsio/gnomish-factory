package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.time.Instant

/**
 * {@code fetchTask} fact-set properties of the {@link Tracker} port contract
 * suite (tracker-port spec, "Task facts from fetchTask"; task 2.4, FR1, FR2).
 * Extends {@link TrackerMarkerContract} to reuse its {@code arrange}/{@code
 * seedTask}/{@code seedReply} seams rather than duplicating them; a concrete
 * adapter subclass instantiates THIS class (not {@link TrackerContract} or
 * {@link TrackerMarkerContract} directly) to run the full suite, per M1 — the
 * same suite against every adapter. Kept in a third file because the combined
 * property set of all three classes would exceed the project's per-file line
 * cap.
 *
 * <p>Covers three spec scenarios: "Full fact set for a working task",
 * an {@code AwaitingHuman(reason)} round-trip counterpart to it, and "Closed
 * task is Gone" — exercised for both a task explicitly seeded as {@code Gone}
 * and a {@link TaskRef} the adapter has never heard of, since the spec treats
 * closed and nonexistent identically ("outside the factory's world
 * entirely").
 *
 * <p>Implements FR1, FR2 of add-tracker-port.
 */
abstract class TrackerFetchContract extends TrackerMarkerContract {

    // FR1: fetchTask returns the exact snapshot, Working(holder) state, and abort
    //     facts seeded for a task ("Full fact set for a working task")
    def "fetchTask returns the full fact set for a working task"() {
        given: 'a tracker seeded with a non-trivial snapshot, a claim holder, and recorded aborts'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'fetchTask full fact set fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:fetch-working')
        def holder = 'instance-a'
        def abortFacts = new AbortFacts(2, Instant.parse('2026-07-20T08:00:00Z'))
        seedTask(adapter, ref, new TrackerTaskState.Working(holder), abortFacts)

        when: 'fetchTask is called'
        def result = adapter.fetchTask(ref)

        then: 'the snapshot id matches the ref, the state is Working(holder), and abort facts round-trip'
        result.ref() == ref
        result.snapshot().id() == ref.id()
        result.state() == new TrackerTaskState.Working(holder)
        result.abortFacts() == abortFacts
    }

    // FR11: fetchTask carries the snapshot's title and body verbatim, not just the id —
    //     the frozen-at-first-claim record a gnome's context and task.json depend on. The
    //     row above only asserts snapshot().id(), so the title/body carry is untested there.
    def "fetchTask carries the snapshot title and body verbatim"() {
        given: 'a tracker seeded with a task whose snapshot has a distinct title and body'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'fetchTask snapshot title/body fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:fetch-snapshot-text')
        def snapshot = new TaskSnapshot(ref.id(), 'implement the widget', 'acceptance: the widget renders')
        seedTask(adapter, ref, snapshot, new TrackerTaskState.Working('instance-a'), AbortFacts.none())

        when: 'fetchTask is called'
        def result = adapter.fetchTask(ref)

        then: 'the whole snapshot round-trips — id, title, and body — exactly as seeded'
        result.snapshot() == snapshot
    }

    // FR2: fetchTask reports AwaitingHuman with the exact park reason it was seeded with
    def "fetchTask returns AwaitingHuman with the seeded reason"() {
        given: 'a tracker seeded with a task parked for a human decision'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'fetchTask AwaitingHuman fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:fetch-awaiting-human')
        seedTask(adapter, ref, new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION), AbortFacts.none())

        when: 'fetchTask is called'
        def result = adapter.fetchTask(ref)

        then: 'the state matches exactly, including the park reason'
        result.state() == new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION)
    }

    // FR1, FR2: a task seeded as closed (Gone) is reported as Gone, not thrown as an
    //     exception ("Closed task is Gone")
    def "fetchTask reports a seeded closed task as Gone without throwing"() {
        given: 'a tracker seeded with a task already closed'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'fetchTask closed fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:fetch-closed')
        seedTask(adapter, ref, new TrackerTaskState.Gone(), AbortFacts.none())

        when: 'fetchTask is called'
        def result = adapter.fetchTask(ref)

        then: 'no exception is thrown, and the state is Gone'
        result.state() == new TrackerTaskState.Gone()
    }

    // FR1, FR2: a TaskRef the adapter has never seeded is ALSO Gone, not thrown — a
    //     missing task is indistinguishable from a closed one, per "outside the
    //     factory's world entirely"
    def "fetchTask reports a never-seeded task as Gone without throwing"() {
        given: 'a tracker adapter with no fixture seeded for this ref at all'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'fetchTask missing-task fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:fetch-never-seeded')

        when: 'fetchTask is called against the unseeded ref'
        def result = adapter.fetchTask(ref)

        then: 'no exception is thrown, and the state is Gone, identically to a closed task'
        result.state() == new TrackerTaskState.Gone()
    }
}
