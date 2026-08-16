package com.github.oinsio.gnomish.adapter.tracker.inmemory

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

/**
 * The state transitions {@code park} and {@code finish} write, the {@code finished} fact derived
 * from correspondence, and the harness reply hook — asserted here on the reference adapter itself.
 *
 * <p>Added by task 8.1 of split-into-modules. Per-module mutation scoping means a module's classes
 * must be covered by that module's own specs; these four behaviours were only ever observed
 * through the {@code InMemoryTake*Spec} lifecycle suites, which drive the whole take flow through
 * the composition root and therefore live in {@code :bootstrap} — a different module's test task.
 * The lifecycle suites still own the end-to-end story; this spec pins the adapter-local facts.
 *
 * <p>Implements FR18 of add-tracker-port; FR1, FR2 of enforce-finish-terminality.
 */
class InMemoryTerminalStateSpec extends AbstractInMemoryTrackerSpec {

    private InMemoryTracker tracker = new InMemoryTracker()
    private InMemoryTrackerHarness harness = new InMemoryTrackerHarness(tracker)

    private TaskRef seedWorking(String id) {
        def ref = new TaskRef(id)
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Working('instance-a'), AbortFacts.none())
        ref
    }

    def "park moves the task to AwaitingHuman carrying the park reason"() {
        given:
        def ref = seedWorking('fixture:park-state')

        when:
        tracker.park(ref, ParkReason.CHECKPOINT, 'waiting on a review')

        then:
        def state = tracker.fetchTask(ref).state()
        state instanceof TrackerTaskState.AwaitingHuman
        (state as TrackerTaskState.AwaitingHuman).reason() == ParkReason.CHECKPOINT
    }

    def "finish moves the task to Finished"() {
        given:
        def ref = seedWorking('fixture:finish-state')

        when:
        tracker.finish(ref, 'delivered: build complete')

        then:
        tracker.fetchTask(ref).state() instanceof TrackerTaskState.Finished
    }

    def "the finished fact is derived from a FINISH entry — a parked task is not finished"() {
        given:
        def finished = seedWorking('fixture:facts-finished')
        def parked = seedWorking('fixture:facts-parked')

        when:
        tracker.finish(finished, 'done')
        tracker.park(parked, ParkReason.CHECKPOINT, 'paused')

        then: 'only the FINISH entry makes the fact true — PARK must not'
        tracker.fetchTask(finished).finished()
        !tracker.fetchTask(parked).finished()
    }

    def "harness reply posts a human reply the adapter then surfaces as a decision"() {
        given:
        def ref = seedWorking('fixture:harness-reply')

        when:
        harness.reply(ref, 'go ahead')

        then:
        tracker.collectDecisions(ref)*.body() == ['go ahead']
    }

    def "a task with no reply posted surfaces no decision"() {
        given:
        def ref = seedWorking('fixture:harness-no-reply')

        expect:
        tracker.collectDecisions(ref).isEmpty()
    }
}
