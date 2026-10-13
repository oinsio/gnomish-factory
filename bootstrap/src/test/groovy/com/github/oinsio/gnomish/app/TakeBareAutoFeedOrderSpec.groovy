package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.take.TakeExitCodeMapper
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch

/**
 * FR6/FR9/D2, D4, D5 of add-factory-serve (task 2.3): the order bare auto {@code take} visits its
 * candidates in, from the shared {@link com.github.oinsio.gnomish.app.take.FeedPolicy} — returned
 * tasks first, fresh tasks gated by the WIP limit both at the initial screening and again at claim
 * time ({@link com.github.oinsio.gnomish.app.take.OpenFrontGate}), and a head-zone pick among the
 * first K. The walk over that order — backoff, claim races, the terminal outcomes — is {@link
 * TakeBareAutoSpec}'s.
 */
class TakeBareAutoFeedOrderSpec extends TakeBareAutoSpecBase {

    // Returned task preferred — an older fresh task and a younger returned task are both
    // ready; the shared FeedPolicy orders the returned task first (FR6), so it is claimed even
    // though it is not the head of the adapter's queue order.
    // Scenario: tracker/tracker-take / Returned task preferred
    def "returned task is claimed ahead of an older fresh task"() {
        given:
        tracker.listReady(_) >> [
            ready('PROJ-1'),
            ready('PROJ-2', AbortFacts.none(), true)
        ]
        tracker.fetchTask(new TaskRef('PROJ-2')) >> trackerTask('PROJ-2')
        def bareAuto = newBareAuto()

        when:
        def result = bareAuto.run(new RunOrder(cloneDir, null, pipeline(), false), tracker, INSTANCE)

        then:
        1 * tracker.claim(new TaskRef('PROJ-2'), INSTANCE.value()) >> new ClaimResult.Acquired(new ClaimEpoch(1))
        0 * tracker.claim(new TaskRef('PROJ-1'), _)
        result instanceof TakeResult.Delivered
    }

    // WIP limit blocks a fresh start — open fronts are at the configured limit and only
    // fresh tasks are ready; bare take claims nothing and ends Skipped (exit 15) naming the WIP limit,
    // distinct from a structurally empty queue (EmptyQueue) and from a claim-race loss (Skipped naming the race).
    // F77: scenario text aligned to what this test asserts
    // Scenario: tracker/tracker-take / WIP limit blocks a fresh start
    def "WIP limit blocks a fresh-only queue: nothing claimed, Skipped with exit 15 naming the limit"() {
        given:
        tracker.listReady(_) >> [ready('PROJ-1')]
        openFronts = [null]
        def bareAuto = newBareAuto(1, headPick())

        when:
        def result = bareAuto.run(new RunOrder(cloneDir, null, pipeline(), false), tracker, INSTANCE)

        then:
        0 * tracker.claim(*_)
        result instanceof TakeResult.Skipped
        TakeExitCodeMapper.exitCodeFor(result) == 15
        def reason = (result as TakeResult.Skipped).reason().forLog().toLowerCase()
        reason.contains('wip') && reason.contains('limit')
    }

    // The per-claim OpenFrontGate re-check (design D5) genuinely re-reads the open-front
    // count instead of trusting the initial snapshot — the count grows between the initial
    // FeedPolicy screening (line-195 tracker.listOpen()) and the fresh candidate's claim-time
    // re-check (OpenFrontGate.isStillEligible's supplier). A List whose size() answers 0 on the
    // first call and 1 on every call after simulates that growth without a second thread: the
    // initial snapshot sees 0 open fronts (fresh task passes FeedPolicy's WIP gate), but by the
    // time the loop re-checks it, the count has reached the wipLimit of 1, so it must be skipped
    // and never claimed. A stubbed lambda that ignored the real re-read (returning the initial 0
    // unconditionally) would wrongly claim it instead.
    def "fresh candidate whose open-front count grew since the initial snapshot is skipped, not claimed"() {
        given:
        tracker.listReady(_) >> [ready('PROJ-1')]
        openFronts = new ArrayList<OpenTask>() {
                    int calls = 0

                    @Override
                    int size() {
                        calls++
                        return calls == 1 ? 0 : 1
                    }
                }
        def bareAuto = newBareAuto(1, headPick())

        when:
        def result = bareAuto.run(new RunOrder(cloneDir, null, pipeline(), false), tracker, INSTANCE)

        then:
        0 * tracker.claim(*_)
        result instanceof TakeResult.Skipped
    }

    // Head-zone pick — a non-head candidate within the first K = 5 eligible entries can
    // be claimed first, proving strict FIFO is no longer enforced beyond the zone (FR9, D4); the
    // remaining candidates keep their original relative order for claim-race fallthrough.
    // Scenario: tracker/tracker-take / One task per run
    def "head-zone pick can claim a non-head candidate first"() {
        given:
        tracker.listReady(_) >> [
            ready('PROJ-1'),
            ready('PROJ-2'),
            ready('PROJ-3')
        ]
        tracker.fetchTask(new TaskRef('PROJ-3')) >> trackerTask('PROJ-3')
        // Index 2 within the K=5 head zone draws PROJ-3 (the third, not the first, entry) to the front.
        def bareAuto = newBareAuto(UNLIMITED_WIP, headPick(2))

        when:
        def result = bareAuto.run(new RunOrder(cloneDir, null, pipeline(), false), tracker, INSTANCE)

        then:
        1 * tracker.claim(new TaskRef('PROJ-3'), INSTANCE.value()) >> new ClaimResult.Acquired(new ClaimEpoch(1))
        0 * tracker.claim(new TaskRef('PROJ-1'), _)
        0 * tracker.claim(new TaskRef('PROJ-2'), _)
        result instanceof TakeResult.Delivered
    }
}
