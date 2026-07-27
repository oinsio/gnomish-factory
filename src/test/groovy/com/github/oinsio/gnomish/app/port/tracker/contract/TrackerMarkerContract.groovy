package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.time.Instant

/**
 * Structural-marker round-trip properties of the {@link Tracker} port
 * contract suite (tracker-port spec, "Port contract spec suite binds every
 * adapter" — structural-marker round-trip; task 2.3, FR4, FR12, FR14,
 * NFR-R3). Extends {@link TrackerContract} to reuse its {@code arrange}/
 * {@code seedTask}/{@code seedReply} seams rather than duplicating them; a
 * concrete adapter subclass instantiates this class (not {@code
 * TrackerContract} directly) to run the FULL suite, per M1 — the same
 * suite against every adapter.
 *
 * <p>Covers two spec scenarios: "Fresh instance sees abort history" (a
 * second port call against the same adapter stands in for "a different
 * instance" — the adapter's job is to make its OWN storage recoverable, not
 * to prove multi-instance-ness, which this layer cannot observe) and "Ack
 * consumes decisions" / "Stale replies never resurface" together.
 *
 * <p>Implements FR4, FR12, FR14, NFR-R3 of add-tracker-port.
 */
abstract class TrackerMarkerContract extends TrackerContract {

    // FR4, FR14, NFR-R3: recordAbort's marker is readable back via fetchTask by a
    //     "different instance" — here, a second port call against the same adapter,
    //     which is exactly what the adapter's own storage must make recoverable
    def "abort facts recorded by one call are readable back through fetchTask"() {
        given: 'a tracker seeded with one Working task and no prior aborts'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'abort round-trip fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:abort-round-trip')
        seedTask(adapter, ref, new TrackerTaskState.Working('instance-a'), AbortFacts.none())
        def record = new AbortRecord('build failed', 'instance-a', Instant.parse('2026-07-20T10:00:00Z'))

        when: 'the abort is recorded and a fresh fetchTask call observes it'
        adapter.recordAbort(ref, record)
        def facts = adapter.fetchTask(ref).abortFacts()

        then: 'the abort count is incremented and the last-abort time reflects the record'
        facts.count() == 1
        facts.lastAbortAt() == record.at()

        and: 'the task is back in Ready, as recordAbort SHALL do in the same operation'
        adapter.fetchTask(ref).state() == new TrackerTaskState.Ready()
    }

    // FR4, FR12: collectDecisions returns a seeded pending reply, and acknowledging it
    //     empties the next collection until a new reply arrives
    def "acknowledging a decision empties collectDecisions until a new reply is posted"() {
        given: 'a tracker seeded with one Working task and one pending human reply'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'decision ack round-trip fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:decision-ack')
        seedTask(adapter, ref, new TrackerTaskState.Working('instance-a'), AbortFacts.none())
        def reply = new HumanReply('proceed with plan B', Instant.parse('2026-07-20T09:00:00Z'))
        seedReply(adapter, ref, reply)

        expect: 'the pending reply comes back before any ack'
        adapter.collectDecisions(ref) == [reply]

        when: 'the factory acknowledges the decision'
        adapter.acknowledgeDecision(ref, reply.body())

        then: 'a subsequent collection is empty — the ack consumed the reply'
        adapter.collectDecisions(ref).isEmpty()
    }

    // FR4, FR12: collectDecisions returns MULTIPLE pending replies in posting order —
    //     the runner replays human decisions in the order they were posted, so a single
    //     reply (the rows above) is not enough to pin the ordering guarantee down
    def "collectDecisions returns multiple pending replies in posting order"() {
        given: 'a tracker seeded with one Working task and three replies posted in sequence'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'decision ordering fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:decision-order')
        seedTask(adapter, ref, new TrackerTaskState.Working('instance-a'), AbortFacts.none())
        def first = new HumanReply('first answer', Instant.parse('2026-07-20T09:00:00Z'))
        def second = new HumanReply('second answer', Instant.parse('2026-07-20T10:00:00Z'))
        def third = new HumanReply('third answer', Instant.parse('2026-07-20T11:00:00Z'))
        seedReply(adapter, ref, first)
        seedReply(adapter, ref, second)
        seedReply(adapter, ref, third)

        expect: 'all three come back in the order they were posted, none dropped or reordered'
        adapter.collectDecisions(ref) == [first, second, third]
    }

    // FR4, FR12: a stale, already-acknowledged reply never resurfaces once a later
    //     reply is posted — collectDecisions returns only what followed the last ack
    def "collectDecisions never resurfaces a reply consumed by an earlier ack"() {
        given: 'a tracker with one reply already seeded and acknowledged'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'stale reply fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:stale-reply')
        seedTask(adapter, ref, new TrackerTaskState.Working('instance-a'), AbortFacts.none())
        def staleReply = new HumanReply('first answer', Instant.parse('2026-07-20T09:00:00Z'))
        seedReply(adapter, ref, staleReply)
        adapter.acknowledgeDecision(ref, staleReply.body())

        when: 'a new human reply is posted after the ack'
        def freshReply = new HumanReply('second answer', Instant.parse('2026-07-20T11:00:00Z'))
        seedReply(adapter, ref, freshReply)

        then: 'collection returns only the fresh reply, never the acknowledged stale one'
        adapter.collectDecisions(ref) == [freshReply]
    }
}
