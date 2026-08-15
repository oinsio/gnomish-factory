package com.github.oinsio.gnomish.adapter.tracker.inmemory

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.time.Instant

/**
 * Implementation-detail properties of {@link InMemoryTracker} that the
 * adapter-agnostic {@code TrackerContract} suite cannot express because they
 * are specific to THIS adapter's coarse-lock storage strategy (design D15,
 * class javadoc): every store-mutating operation must fully release {@link
 * InMemoryTracker#lock} on exit — verified here by proving a second thread can
 * acquire it immediately afterward — and the {@code Gone} snapshot synthesized
 * for a task the adapter never heard of / no longer holds has the specific
 * shape {@code fetchTask} promises (FR1, FR2).
 *
 * <p>Implements FR1, FR2, FR3 of add-tracker-port.
 */
class InMemoryTrackerSpec extends AbstractInMemoryTrackerSpec {

    def "acknowledgeDecision fully releases the store lock on exit"() {
        given: 'a tracker with one seeded task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:ack-unlock')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Working('instance-a'), AbortFacts.none())

        when: 'acknowledgeDecision returns'
        tracker.acknowledgeDecision(ref, 'irrelevant')

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    def "collectDecisions fully releases the store lock on exit"() {
        given: 'a tracker with one seeded task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:collect-unlock')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Working('instance-a'), AbortFacts.none())

        when: 'collectDecisions returns'
        tracker.collectDecisions(ref)

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    def "fetchTask fully releases the store lock on exit"() {
        given: 'a tracker with one seeded task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:fetch-unlock')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Working('instance-a'), AbortFacts.none())

        when: 'fetchTask returns'
        tracker.fetchTask(ref)

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    def "recordAbort fully releases the store lock on exit"() {
        given: 'a tracker with one seeded task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:abort-unlock')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Working('instance-a'), AbortFacts.none())

        when: 'recordAbort returns'
        tracker.recordAbort(ref, new AbortRecord('boom', 'instance-a', Instant.parse('2026-07-20T10:00:00Z')))

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    def "recordProgress fully releases the store lock on exit"() {
        given: 'a tracker with one seeded task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:progress-unlock')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Working('instance-a'), new AbortFacts(2, Instant.parse('2026-07-20T10:00:00Z')))

        when: 'recordProgress returns'
        tracker.recordProgress(ref)

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    // FR1, FR3, D1-D4: recordProgress after an abort clears the abort streak, narrates the
    //     reset in the thread, and leaves the task's logical state untouched.
    def "recordProgress after an abort zeroes the abort count, appends a PROGRESS entry, and does not change state"() {
        given: 'a tracker with one seeded Working task carrying abort history'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:progress-reset')
        def workingState = new TrackerTaskState.Working('instance-a')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), workingState, new AbortFacts(2, Instant.parse('2026-07-20T10:00:00Z')))

        when: 'recordProgress is called'
        tracker.recordProgress(ref)

        then: 'the abort count and last-abort timestamp are reset'
        def facts = tracker.fetchTask(ref).abortFacts()
        facts.count() == 0
        facts.lastAbortAt() == null

        and: 'the thread carries a PROGRESS entry'
        harness.thread(ref)*.kind() == [
            CorrespondenceEntry.Kind.PROGRESS
        ]

        and: 'the task remains in its prior logical state, untouched'
        tracker.fetchTask(ref).state() == workingState
    }

    def "an armed claim gate runs before claim competes for the store lock"() {
        given: 'a tracker with one seeded ready task and an armed claim gate'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:claim-gate')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Ready(), AbortFacts.none())
        boolean gateRan = false
        harness.armClaimGate { gateRan = true }

        when: 'claim is called'
        tracker.claim(ref, 'instance-a')

        then: 'the armed gate ran before the claim proceeded'
        gateRan
    }

    def "disarming the claim gate restores normal claim behavior"() {
        given: 'a tracker with one seeded ready task, a claim gate armed then disarmed'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:claim-gate-disarm')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Ready(), AbortFacts.none())
        boolean gateRan = false
        harness.armClaimGate { gateRan = true }
        harness.disarmClaimGate()

        when: 'claim is called'
        tracker.claim(ref, 'instance-a')

        then: 'the disarmed gate never ran'
        !gateRan
    }

    def "claim fully releases the store lock on exit"() {
        given: 'a tracker with one seeded ready task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:claim-unlock')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Ready(), AbortFacts.none())

        when: 'claim returns'
        tracker.claim(ref, 'instance-a')

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    def "release fully releases the store lock on exit"() {
        given: 'a tracker with one seeded task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:release-unlock')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Working('instance-a'), AbortFacts.none())

        when: 'release returns'
        tracker.release(ref)

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    def "park fully releases the store lock on exit"() {
        given: 'a tracker with one seeded task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:park-unlock')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Working('instance-a'), AbortFacts.none())

        when: 'park returns'
        tracker.park(ref, ParkReason.CHECKPOINT, 'paused for review')

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    def "park records the given report text on the tracked task"() {
        given: 'a tracker with one seeded task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:park-report')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Working('instance-a'), AbortFacts.none())

        when: 'park is called with a report'
        tracker.park(ref, ParkReason.CHECKPOINT, 'paused for review')

        then: 'the report text is stored on the task, retrievable by any instance'
        tracker.store.get(ref).report() == 'paused for review'
    }

    def "finish fully releases the store lock on exit"() {
        given: 'a tracker with one seeded task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:finish-unlock')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Working('instance-a'), AbortFacts.none())

        when: 'finish returns'
        tracker.finish(ref, 'delivered')

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    def "finish records the given summary text on the tracked task"() {
        given: 'a tracker with one seeded task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:finish-summary')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Working('instance-a'), AbortFacts.none())

        when: 'finish is called with a summary'
        tracker.finish(ref, 'delivered')

        then: 'the summary text is stored on the task, retrievable by any instance'
        tracker.store.get(ref).summary() == 'delivered'
    }

    def "postNote fully releases the store lock on exit"() {
        given: 'a tracker with one seeded task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:note-unlock')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Working('instance-a'), AbortFacts.none())

        when: 'postNote returns'
        tracker.postNote(ref, 'a note')

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    def "listReady rejects a zero limit at the exact boundary, not just negative values"() {
        given: 'a tracker with no seeded tasks'
        def tracker = new InMemoryTracker()

        when: 'listReady is called with the boundary value zero'
        tracker.listReady(0)

        then: 'zero is rejected exactly like a negative limit'
        thrown(IllegalArgumentException)
    }

    def "listReady fully releases the store lock on exit"() {
        given: 'a tracker with one seeded ready task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:list-unlock')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Ready(), AbortFacts.none())

        when: 'listReady returns'
        tracker.listReady(10)

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    // FR1, FR2: fetchTask synthesizes a Gone snapshot for an unknown/gone ref whose id
    //     and title both echo the ref's own id, with an empty body — never a blank
    //     placeholder title and never a null snapshot
    def "fetchTask synthesizes a Gone snapshot whose id and title both echo the ref, with an empty body"() {
        given: 'a tracker that never heard of this ref'
        def tracker = new InMemoryTracker()
        def ref = new TaskRef('fixture:never-seeded-snapshot')

        when: 'fetchTask is called'
        def result = tracker.fetchTask(ref)

        then: 'the synthesized snapshot carries the exact expected fields'
        result.snapshot() == new TaskSnapshot(ref.id(), ref.id(), '')
    }
}
