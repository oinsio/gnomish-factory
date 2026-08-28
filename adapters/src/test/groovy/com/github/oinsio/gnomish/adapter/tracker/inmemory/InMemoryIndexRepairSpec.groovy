package com.github.oinsio.gnomish.adapter.tracker.inmemory

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult
import com.github.oinsio.gnomish.app.port.tracker.StateLabels
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.time.Instant
import spock.lang.Specification

/**
 * The reference adapter's index repair and generalized stale-claim removal (FR19, FR12 of
 * harden-task-branch-contract): the labels are brought to what the recorded truth implies, guarded
 * by the caller's observation, and a footprint no tenure backs is retired whether or not a live
 * version remains.
 */
class InMemoryIndexRepairSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')

    private InMemoryTracker tracker = new InMemoryTracker()
    private InMemoryTrackerHarness harness = new InMemoryTrackerHarness(tracker)

    def setup() {
        harness.seed(REF, new TaskSnapshot(REF.id(), 'title', 'body'),
                new TrackerTaskState.Working('inst-1'), AbortFacts.none())
    }

    // FR12: the claim-pending rollback — a working task with no footprint returns to Ready, and the
    //     repair is recorded in the thread as its own non-boundary entry.
    def "a claimless working task is repaired to Ready, with the repair recorded"() {
        given:
        def observed = TrackerFacts.of(StateLabels.workingOnly())

        when:
        def result = tracker.repairIndex(REF, observed)

        then:
        result instanceof RepairIndexResult.Repaired
        (result as RepairIndexResult.Repaired).facts().labels() == StateLabels.readyOnly()
        tracker.fetchTask(REF).state() == new TrackerTaskState.Ready()
        harness.thread(REF).any {
            it.kind() == CorrespondenceEntry.Kind.INDEX_REPAIR
        }
    }

    // FR12: the lagging-index completion — the flip the newest boundary implies is finished, and no
    //     recorded work is re-executed.
    def "a #kind boundary's flip is completed to #expected"() {
        given: 'the truth marker landed while the labels still read working'
        tracker.withLock({
            ->
            def task = trackedTask()
            task.note(kind, 'boundary')
            null
        })
        def observed = TrackedTaskFacts.facts(trackedTask())

        when:
        def result = tracker.repairIndex(REF, observed)

        then:
        result instanceof RepairIndexResult.Repaired
        tracker.fetchTask(REF).state() == expected

        where:
        kind || expected
        CorrespondenceEntry.Kind.ABORT || new TrackerTaskState.Ready()
        CorrespondenceEntry.Kind.STALE_CLAIM_REMOVED || new TrackerTaskState.Ready()
        CorrespondenceEntry.Kind.FINISH || new TrackerTaskState.Finished()
    }

    // A park marker's flip lands on the reason the park itself recorded — a repair completes a
    // transition, it never invents an escalation the task never had.
    def "a park boundary's flip lands on the recorded park reason"() {
        given: 'the task was parked for a checkpoint, then dragged back to working out of band'
        tracker.park(REF, ParkReason.CHECKPOINT, 'report')
        tracker.withLock({
            ->
            trackedTask().state(new TrackerTaskState.Working('inst-1'))
            null
        })
        def observed = TrackedTaskFacts.facts(trackedTask())

        when:
        def result = tracker.repairIndex(REF, observed)

        then:
        result instanceof RepairIndexResult.Repaired
        tracker.fetchTask(REF).state() == new TrackerTaskState.AwaitingHuman(ParkReason.CHECKPOINT)
    }

    // With no park ever recorded, an infrastructure park is the neutral reason to complete on — the
    // repair carries no claim about why a human is needed beyond the marker it is completing.
    def "a park boundary with no recorded reason completes as an infrastructure park"() {
        given:
        tracker.withLock({
            ->
            trackedTask().note(CorrespondenceEntry.Kind.PARK, 'boundary')
            null
        })
        def observed = TrackedTaskFacts.facts(trackedTask())

        when:
        tracker.repairIndex(REF, observed)

        then:
        tracker.fetchTask(REF).state() == new TrackerTaskState.AwaitingHuman(ParkReason.INFRA)
    }

    def "a repair whose observation no longer holds writes nothing"() {
        given: 'the caller observed a claimless window, but a claim landed since'
        def observed = TrackerFacts.of(StateLabels.workingOnly())
        harness.returnToReady(REF)
        tracker.claim(REF, 'inst-2')

        when:
        def result = tracker.repairIndex(REF, observed)

        then:
        result instanceof RepairIndexResult.Unchanged
        (result as RepairIndexResult.Unchanged).facts().claim() instanceof ClaimFacts.Live
        tracker.fetchTask(REF).state() == new TrackerTaskState.Working('inst-2')
        !harness.thread(REF).any {
            it.kind() == CorrespondenceEntry.Kind.INDEX_REPAIR
        }
    }

    def "a repair of a task the tracker no longer holds reports the observation back, unchanged"() {
        given:
        def observed = TrackerFacts.of(StateLabels.workingOnly())

        when:
        def result = tracker.repairIndex(new TaskRef('PROJ-404'), observed)

        then:
        result == new RepairIndexResult.Unchanged(observed)
    }

    // FR19: a dead footprint is an eligible removal input — there is no comment to delete, and the
    //     boundary plus the flip still retire it.
    def "a dead footprint is retired by the stale-claim removal"() {
        given: 'the claim marker is gone while the task still reads working'
        harness.returnToReady(REF)
        tracker.claim(REF, 'inst-1')
        tracker.release(REF)
        tracker.withLock({
            ->
            trackedTask().state(new TrackerTaskState.Working('inst-1'))
            null
        })
        def observed = TrackedTaskFacts.claim(trackedTask())

        when:
        def result = tracker.removeStaleClaim(REF, observed)

        then:
        observed == new ClaimFacts.Dead('inst-1')
        result instanceof RemoveStaleClaimResult.Removed
        tracker.fetchTask(REF).state() == new TrackerTaskState.Ready()
        harness.thread(REF).any {
            it.kind() == CorrespondenceEntry.Kind.STALE_CLAIM_REMOVED
        }
    }

    // With no footprint at all there is nothing to retire: reporting the absent facts converges
    //     rather than posting a boundary for a tenure that left no trace.
    def "an absent footprint is a converging no-op, not a boundary"() {
        when:
        def result = tracker.removeStaleClaim(REF, new ClaimFacts.None())

        then:
        result == new RemoveStaleClaimResult.Mismatch(null)
        !harness.thread(REF).any {
            it.kind() == CorrespondenceEntry.Kind.STALE_CLAIM_REMOVED
        }
        tracker.fetchTask(REF).state() == new TrackerTaskState.Working('inst-1')
    }

    def "recordAbort keeps its own abort instant, unrelated to the repair"() {
        when:
        tracker.recordAbort(REF, new AbortRecord('cause', 'inst-1', Instant.EPOCH))

        then:
        tracker.fetchTask(REF).abortFacts().count() == 1
    }

    private TrackedTask trackedTask() {
        tracker.store.get(REF)
    }
}
