package com.github.oinsio.gnomish.adapter.tracker.inmemory

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * The repair entry of the reference adapter names the shape the repair observed (FR12 of
 * harden-task-branch-contract), read back from the task's recorded thread: a claimless working task
 * is a pending claim, a working state under a boundary entry is an index lagging behind it.
 */
class InMemoryRepairMarkerSpec extends Specification {

    private static final TaskRef REF = new TaskRef('PROJ-1')

    private InMemoryTracker tracker = new InMemoryTracker()
    private InMemoryTrackerHarness harness = new InMemoryTrackerHarness(tracker)

    // FR12: the structural repair marker names the observed shape — ClaimPending or IndexLagging
    // F81: the reference adapter recorded the raw facts record ("index repaired from TrackerFacts[...]"), naming
    //     neither shape; it now renders the GitHub adapter's shape phrase (a declared pair)
    // Scenario: tracker/tracker-port / The repair marker names the observed shape
    def "the repair entry of a #shape repair names that shape"() {
        given:
        shape == 'claim pending' ? claimless() : finishedUnderWorkingState()
        def observed = TrackedTaskFacts.facts(tracker.store.get(REF))

        when:
        def result = tracker.repairIndex(REF, observed)

        then:
        result instanceof RepairIndexResult.Repaired
        def entry = harness.thread(REF).find {
            it.kind() == CorrespondenceEntry.Kind.INDEX_REPAIR
        }
        entry.text().toLowerCase(Locale.ROOT).contains(shape)

        where:
        shape << [
            'claim pending',
            'index lagging'
        ]
    }

    private void claimless() {
        harness.seed(REF, snapshot(), new TrackerTaskState.Working('inst-1'), AbortFacts.none())
    }

    private void finishedUnderWorkingState() {
        harness.seed(REF, snapshot(), new TrackerTaskState.Ready(), AbortFacts.none())
        tracker.claim(REF, 'inst-1')
        tracker.finish(REF, 'summary: shipped it')
        tracker.withLock({
            ->
            tracker.store.get(REF).state(new TrackerTaskState.Working('inst-1'))
            null
        })
    }

    private static TaskSnapshot snapshot() {
        new TaskSnapshot(REF.id(), UntrustedText.tracker('title'), UntrustedText.tracker('body'))
    }
}
