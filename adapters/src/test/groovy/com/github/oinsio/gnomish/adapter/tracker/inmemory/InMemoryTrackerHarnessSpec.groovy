package com.github.oinsio.gnomish.adapter.tracker.inmemory

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.time.Instant

/**
 * Implementation-detail properties of {@link InMemoryTrackerHarness} that the
 * port contract suite does not exercise directly: the exact boundary at which
 * {@link InMemoryTrackerHarness#seed} decides to replay {@code recordAbort}
 * calls (task 2.6), and that {@link InMemoryTrackerHarness#seedReply} fully
 * releases the wrapped adapter's store lock on exit, mirroring {@link
 * InMemoryTrackerSpec}'s lock-release properties for the adapter itself. Also
 * covers {@link InMemoryTrackerHarness#edit} — the human-edits-the-issue
 * simulation — and, through it, FR11's "snapshot at first claim": an edit to
 * the live issue leaves a claim-time snapshot untouched.
 *
 * <p>Implements FR3, FR11 of add-tracker-port.
 */
class InMemoryTrackerHarnessSpec extends AbstractInMemoryTrackerSpec {

    // task 2.6: seed only replays recordAbort when count is STRICTLY positive — a zero
    //     count must never call recordAbort, even when lastAbortAt is (irregularly) non-null
    def "seed does not replay recordAbort when abort count is exactly zero"() {
        given: 'a tracker and harness, and an irregular AbortFacts pairing zero count with a non-null timestamp'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:seed-zero-count')
        def abortFacts = new AbortFacts(0, Instant.parse('2026-07-20T10:00:00Z'))

        when: 'the task is seeded'
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Ready(), abortFacts)

        then: 'the seeded task reports zero aborts — recordAbort was never replayed'
        tracker.fetchTask(ref).abortFacts().count() == 0
    }

    def "seedReply fully releases the store lock on exit"() {
        given: 'a tracker and harness with one seeded task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:seed-reply-unlock')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Working('instance-a'), AbortFacts.none())

        when: 'seedReply returns'
        harness.seedReply(tracker, ref, new HumanReply('proceed', Instant.parse('2026-07-20T09:00:00Z')))

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    def "returnToReady moves an AwaitingHuman task back to Ready and fully releases the store lock"() {
        given: 'a tracker and harness with one seeded parked task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:return-to-ready')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION), AbortFacts.none())

        when: 'a human returns the task to ready'
        harness.returnToReady(ref)

        then: 'the task is Ready again, and a different thread can immediately acquire the lock'
        tracker.fetchTask(ref).state() == new TrackerTaskState.Ready()
        lockIsFreeFromAnotherThread(tracker)
    }

    def "reopenFinished moves a Finished task back to Ready and fully releases the store lock"() {
        given: 'a tracker and harness with one seeded finished task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:reopen-finished')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Finished(), AbortFacts.none())

        when: 'a human reopens the finished task'
        harness.reopenFinished(ref)

        then: 'the task is Ready again, and a different thread can immediately acquire the lock'
        tracker.fetchTask(ref).state() == new TrackerTaskState.Ready()
        lockIsFreeFromAnotherThread(tracker)
    }

    def "close moves a task to Gone and fully releases the store lock"() {
        given: 'a tracker and harness with one seeded task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:close')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Ready(), AbortFacts.none())

        when: 'a human closes the task'
        harness.close(ref)

        then: 'the task is Gone, and a different thread can immediately acquire the lock'
        tracker.fetchTask(ref).state() == new TrackerTaskState.Gone()
        lockIsFreeFromAnotherThread(tracker)
    }

    // FR3: edit rewrites the live issue's title and body (the id stays the ref's identity)
    //     and fully releases the store lock, mirroring the other human-simulation ops.
    def "edit rewrites the live issue title and body and fully releases the store lock"() {
        given: 'a tracker and harness with one seeded, claimed task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:edit')
        harness.seed(ref, new TaskSnapshot(ref.id(), 'original title', 'original body'), new TrackerTaskState.Working('instance-a'), AbortFacts.none())

        when: 'a human edits the issue title and body'
        harness.edit(ref, 'rewritten title', 'rewritten body')

        then: 'fetchTask reflects the new title and body, the id is unchanged, and the lock is free'
        def snapshot = tracker.fetchTask(ref).snapshot()
        snapshot.id() == ref.id()
        snapshot.title() == 'rewritten title'
        snapshot.body() == 'rewritten body'
        lockIsFreeFromAnotherThread(tracker)
    }

    // FR11 "Issue edited mid-task": a snapshot captured at first claim is a frozen value —
    //     editing the live issue afterwards (while Working or parked) changes what fetchTask
    //     returns but NOT the already-captured claim-time snapshot the gnome's context and
    //     task.json hold. This is the "snapshot at first claim" guarantee, exercised through
    //     the harness's edit operation.
    def "an issue edited after first claim does not affect the claim-time snapshot"() {
        given: 'a tracker and harness with one seeded, claimed task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:edit-mid-task')
        harness.seed(ref, new TaskSnapshot(ref.id(), 'claim-time title', 'claim-time body'), new TrackerTaskState.Working('instance-a'), AbortFacts.none())

        and: 'the snapshot the factory froze at first claim — the value that flows into task.json'
        def claimTimeSnapshot = tracker.fetchTask(ref).snapshot()

        when: 'a human edits the issue title and body while the task is in flight'
        harness.edit(ref, 'edited title', 'edited body')

        then: 'the live tracker reflects the edit'
        def live = tracker.fetchTask(ref).snapshot()
        live.title() == 'edited title'
        live.body() == 'edited body'

        and: 'the claim-time snapshot is untouched — a frozen copy, immune to the later edit'
        claimTimeSnapshot.id() == ref.id()
        claimTimeSnapshot.title() == 'claim-time title'
        claimTimeSnapshot.body() == 'claim-time body'
    }

    def "thread fully releases the store lock on exit"() {
        given: 'a tracker and harness with one seeded task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:thread-unlock')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Ready(), AbortFacts.none())

        when: 'thread returns'
        harness.thread(ref)

        then: 'a different thread can immediately acquire the lock, proving it was released'
        lockIsFreeFromAnotherThread(tracker)
    }

    // FR18, M3, UX4 of add-tracker-port: claim -> park -> finish appends one ordered
    //     correspondence entry per write, so the tracker's own state (not the test's own
    //     bookkeeping) tells the whole story of a task in order.
    def "thread records claim, park, and finish in order, each as its own entry"() {
        given: 'a tracker with one seeded Ready task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:thread-order')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Ready(), AbortFacts.none())

        when: 'the task is claimed, parked, and finished'
        tracker.claim(ref, 'instance-a')
        tracker.park(ref, ParkReason.CHECKPOINT, 'waiting on a review')
        tracker.finish(ref, 'delivered: build complete')

        then: 'the thread reports exactly those three writes, in that order'
        def thread = harness.thread(ref)
        thread.size() == 3
        thread*.kind() == [
            CorrespondenceEntry.Kind.CLAIM,
            CorrespondenceEntry.Kind.PARK,
            CorrespondenceEntry.Kind.FINISH
        ]
    }

    // FR18, M3, UX4: recordAbort and acknowledgeDecision each append their own kind too,
    //     and release() (design D2: leaves logical state untouched) appends nothing.
    def "thread records recordAbort and acknowledgeDecision, and release appends nothing"() {
        given: 'a tracker with one seeded Working task and a pending reply'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:thread-abort-ack')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Working('instance-a'), AbortFacts.none())
        harness.reply(ref, 'go ahead')

        when: 'the task is released, then acknowledged, then aborted'
        tracker.release(ref)
        tracker.acknowledgeDecision(ref, 'go ahead')
        tracker.recordAbort(ref, new AbortRecord('network blip', 'instance-a', Instant.parse('2026-07-20T10:00:00Z')))

        then: 'release appends nothing; acknowledgeDecision and recordAbort each appear once, in order'
        harness.thread(ref)*.kind() == [
            CorrespondenceEntry.Kind.ACK,
            CorrespondenceEntry.Kind.ABORT
        ]
    }

    // FR18, M3, UX4: postNote is correspondence-only, but it still belongs in the thread —
    //     otherwise a note a human or the factory posts would be invisible to "the issue
    //     thread alone tells the story."
    def "thread records postNote"() {
        given: 'a tracker with one seeded task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:thread-note')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Ready(), AbortFacts.none())

        when: 'a note is posted'
        tracker.postNote(ref, 'still working on it')

        then: 'the thread reports one NOTE entry'
        harness.thread(ref)*.kind() == [CorrespondenceEntry.Kind.NOTE]
    }

    // FR18, M3, UX4: the thread is self-sufficient — the entry text itself narrates the
    //     fact, not just its kind, matching UX4's "readable without access to factory logs."
    def "each thread entry's text narrates the fact it records, not just its kind"() {
        given: 'a tracker with one seeded Ready task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:thread-text')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Ready(), AbortFacts.none())

        when: 'the task is claimed and then finished with a summary'
        tracker.claim(ref, 'instance-a')
        tracker.finish(ref, 'delivered: build complete')

        then: 'the claim entry names the claiming instance and the finish entry carries the summary'
        def thread = harness.thread(ref)
        thread[0].text().contains('instance-a')
        thread[1].text().contains('delivered: build complete')
    }

    def "threadAsStrings formats each entry as 'kind: text', oldest first"() {
        given: 'a tracker with one seeded Ready task'
        def tracker = new InMemoryTracker()
        def harness = new InMemoryTrackerHarness(tracker)
        def ref = new TaskRef('fixture:thread-as-strings')
        harness.seed(ref, new TaskSnapshot(ref.id(), 't', 'b'), new TrackerTaskState.Ready(), AbortFacts.none())

        when: 'the task is claimed and then finished with a summary'
        tracker.claim(ref, 'instance-a')
        tracker.finish(ref, 'delivered: build complete')

        then: 'each formatted line pairs the entry kind with its narrating text, in order'
        harness.threadAsStrings(ref) == [
            "${CorrespondenceEntry.Kind.CLAIM}: ${harness.thread(ref)[0].text()}".toString(),
            "${CorrespondenceEntry.Kind.FINISH}: ${harness.thread(ref)[1].text()}".toString()
        ]
    }
}
