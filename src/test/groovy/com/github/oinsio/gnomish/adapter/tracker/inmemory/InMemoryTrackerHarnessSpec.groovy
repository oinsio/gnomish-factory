package com.github.oinsio.gnomish.adapter.tracker.inmemory

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import java.time.Instant
import spock.lang.Specification

/**
 * Implementation-detail properties of {@link InMemoryTrackerHarness} that the
 * port contract suite does not exercise directly: the exact boundary at which
 * {@link InMemoryTrackerHarness#seed} decides to replay {@code recordAbort}
 * calls (task 2.6), and that {@link InMemoryTrackerHarness#seedReply} fully
 * releases the wrapped adapter's store lock on exit, mirroring {@link
 * InMemoryTrackerSpec}'s lock-release properties for the adapter itself.
 *
 * <p>Implements FR3 of add-tracker-port.
 */
class InMemoryTrackerHarnessSpec extends Specification {

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

    /** Proves {@code tracker.lock} is NOT held, from a thread other than the caller's own. */
    private static boolean lockIsFreeFromAnotherThread(InMemoryTracker tracker) {
        boolean[] acquired = [false]
        Thread thread = Thread.ofVirtual().unstarted {
            if (tracker.lock.tryLock()) {
                acquired[0] = true
                tracker.lock.unlock()
            }
        }
        thread.start()
        thread.join(2000)
        acquired[0]
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
}
