package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState

/**
 * The finish-reopen and decline-protocol properties of the {@link Tracker}
 * port contract suite (enforce-finish-terminality; FR2, FR4, FR6, NFR-R1,
 * NFR-R2, NFR-O1, UX2). Extends {@link TrackerReturnedFactContract} — the
 * previously most-derived link in the chain — to reuse every seam it
 * accumulates ({@code arrange}, {@code seedTask}, {@code seedReply}, {@code
 * seedWorkingWithClaim}, {@code returnToReady}) rather than duplicating them;
 * a concrete adapter subclass instantiates THIS class (not {@link
 * TrackerReturnedFactContract} or any earlier link directly) to run the full
 * suite, per M1 — the same suite against every adapter. Kept in its own file
 * because the combined property set of the whole chain would exceed the
 * project's per-file line cap.
 *
 * <p>Two new seams: {@link #reopenFinished} (a human moving a {@code
 * Finished} task back to {@code Ready}, mirroring {@link
 * TrackerReturnedFactContract#returnToReady}) and {@link #postedTexts} (a
 * read-back of every comment/note body posted on a task, since no port
 * operation exposes arbitrary posted text — {@code collectDecisions}
 * surfaces only human replies, a different concept).
 *
 * <p>Implements FR2, FR4, FR6, NFR-R1, NFR-R2, NFR-O1, UX2 of
 * enforce-finish-terminality.
 */
abstract class TrackerFinishContract extends TrackerReturnedFactContract {

    /**
     * Simulates a human moving a {@code Finished} task back to {@code Ready}
     * directly in the tracker UI — the only way that transition happens (the
     * factory itself never resumes a finished task, NG1 of
     * enforce-finish-terminality). MUST leave the task's recorded
     * correspondence history (the finish report/marker) intact — only the
     * logical state moves — mirroring {@link
     * TrackerReturnedFactContract#returnToReady}'s contract exactly but for
     * the opposite terminal state.
     *
     * @param adapter the tracker adapter arranged by {@link #arrange}
     * @param ref the fixture task's identity; must already be seeded and
     *     currently {@code Finished}
     */
    protected abstract void reopenFinished(Tracker adapter, TaskRef ref)

    /**
     * Reads back every comment/note body posted on {@code ref}, in posting
     * order — the test-only window onto posted text no {@link Tracker}
     * operation itself exposes (unlike {@code collectDecisions}, which
     * surfaces only human replies). Used to assert a decline's explanation
     * was actually posted, and that a no-op decline posts nothing new.
     *
     * @param adapter the tracker adapter arranged by {@link #arrange}
     * @param ref the fixture task's identity; must already be seeded
     * @return every posted comment/note body, in posting order, possibly empty; never null
     */
    protected abstract List<String> postedTexts(Tracker adapter, TaskRef ref)

    // FR2, FR6: a task claimed, finished, then reopened by a human carries a finish-report
    //     history — its returned fact stays false (a finish is never a "returned" marker) and
    //     its finished fact is true ("Finish-reopen sets both facts")
    def "listReady reports finished = true and returned = false after a finish-reopen round-trip"() {
        given: 'a task claimed and finished, then moved back to Ready by a human'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'finish-reopen fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:finish-reopen')
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        adapter.finish(ref, 'summary: shipped it')

        when: 'a human moves the finished task back to Ready, and listReady is called'
        reopenFinished(adapter, ref)
        List<ReadyTask> result = adapter.listReady(10)

        then: 'the entry reports finished = true and returned = false — the finish report is not a returned marker'
        def entry = result.find { it.ref() == ref }
        entry.returned() == false
        entry.finished() == true
    }

    // FR2: a task seeded straight into Ready, never finished, carries no finish-report
    //     history — its finished fact is false ("Never-finished task")
    def "listReady reports finished = false for a task that was never finished"() {
        given: 'a tracker seeded with one Ready task that was never finished'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'never-finished fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:finish-never')
        seedTask(adapter, ref, new TrackerTaskState.Ready(), AbortFacts.none())

        when: 'listReady is called'
        List<ReadyTask> result = adapter.listReady(10)

        then: 'the entry reports finished = false'
        result.find { it.ref() == ref }.finished() == false
    }

    // FR4, NFR-R1, NFR-O1, UX2: declining a reopened finished task restores its terminal status,
    //     removes it from the Ready feed, and posts a visible explanation ("Decline round-trip")
    def "declineFinished restores terminal status, clears the feed, and posts a visible explanation"() {
        given: 'a task finished, then reopened by a human, now Ready with finish history'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'decline round-trip fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:decline-round-trip')
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        adapter.finish(ref, 'summary: shipped it')
        reopenFinished(adapter, ref)
        def explanation = 'this task is already finished; open a new task or bug for further changes'

        when: 'the factory declines the reopened task'
        adapter.declineFinished(ref, explanation)

        then: 'the terminal status is restored, the task leaves the Ready feed, and the explanation is visible'
        adapter.fetchTask(ref).state() instanceof TrackerTaskState.Finished
        adapter.listReady(10).find { it.ref() == ref } == null
        postedTexts(adapter, ref).any { it.contains(explanation) }
    }

    // NFR-R1: declining an already-terminal task is a state-level no-op — status unchanged, no
    //     explanation posted ("Decline of already-terminal task")
    def "declineFinished on an already-terminal task is a silent no-op"() {
        given: 'a task that is already Finished, never reopened'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'decline no-op fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:decline-noop')
        seedTask(adapter, ref, new TrackerTaskState.Finished(), AbortFacts.none())
        def before = postedTexts(adapter, ref)

        when: 'declineFinished is called on the already-terminal task'
        adapter.declineFinished(ref, 'this task is already finished; open a new task or bug for further changes')

        then: 'status is unchanged and nothing new was posted'
        adapter.fetchTask(ref).state() instanceof TrackerTaskState.Finished
        postedTexts(adapter, ref) == before
    }

    // FR2, FR6, D1, D3: the posted decline explanation must never feed the returned/finished
    //     derivations ("Decline explanation is derivation-neutral") — reopening the SAME task a
    //     second time after a decline still reports finished = true, returned = false
    def "finished and returned facts survive a decline explanation on a second reopen"() {
        given: 'a task finished, reopened, and declined once'
        def tracker = arrange()
        assumeProducible(tracker, 'Tracker', 'decline derivation-neutral fixture')
        def adapter = tracker.get()
        def ref = new TaskRef('fixture:decline-derivation-neutral')
        seedWorkingWithClaim(adapter, ref, 'instance-a')
        adapter.finish(ref, 'summary: shipped it')
        reopenFinished(adapter, ref)
        adapter.declineFinished(ref, 'this task is already finished; open a new task or bug for further changes')

        when: 'a human reopens the same task a second time, and listReady is called'
        reopenFinished(adapter, ref)
        List<ReadyTask> result = adapter.listReady(10)

        then: 'the decline explanation was not miscounted as a park or second finish report'
        def entry = result.find { it.ref() == ref }
        entry.finished() == true
        entry.returned() == false
    }
}
