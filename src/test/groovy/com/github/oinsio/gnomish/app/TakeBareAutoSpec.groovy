package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * FR10, NFR-C1 of add-tracker-port (task 5.10), FR6/FR9/D2 of add-factory-serve (task 2.3): {@link
 * TakeBareAuto#run} — bare auto {@code take} claims from the shared {@link
 * com.github.oinsio.gnomish.app.take.FeedPolicy} candidate ordering (returned tasks first, fresh
 * tasks gated by the WIP limit, a head-zone pick among the first K), applies abort backoff, claims
 * exactly one task, and exits; a lost claim race (or a per-claim {@link
 * com.github.oinsio.gnomish.app.take.OpenFrontGate} rejection) falls through to the next
 * candidate; an empty post-filter queue terminates with {@link TakeResult.EmptyQueue} (design D16:
 * the clean cron no-op, exit 0); a queue whose only eligible tasks are fresh and WIP-blocked
 * terminates with a WIP-naming {@link TakeResult.Skipped}; an all-raced queue — candidates existed
 * but none could be claimed — terminates with a race-naming {@link TakeResult.Skipped} (a
 * refusal-shaped outcome, exit 15). Mirrors the tracker-take spec's "Bare auto mode takes the head
 * of the queue" scenarios.
 */
class TakeBareAutoSpec extends TakeResumeSpecBase {

    private static final Duration BASE = Duration.ofMinutes(2)
    private static final Duration CAP = Duration.ofHours(1)
    private static final Instant NOW = Instant.parse('2026-07-23T12:00:00Z')
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)

    /** Never blocks on the WIP limit — the default for scenarios that carry no WIP concern. */
    private static final int UNLIMITED_WIP = Integer.MAX_VALUE

    /** Fixes {@link com.github.oinsio.gnomish.app.take.FeedPolicy}'s head-zone pick to a chosen
     *  index; the default (0) preserves strict oldest-first order for scenarios that don't
     *  exercise the shuffle. */
    private static Random headPick(int index = 0) {
        new Random() {
                    @Override
                    int nextInt(int bound) {
                        return index
                    }
                }
    }

    private TakeBareAuto newBareAuto(int wipLimit = UNLIMITED_WIP, Random random = headPick()) {
        def abortHandler = new AbortHandler(tracker, Clock.systemUTC())
        new TakeBareAuto(
                newAssembly(), worktreesRoot, abortHandler, ABORT_THRESHOLD, 'taskId', BASE, CAP, CLOCK, [],
                wipLimit, random)
    }

    private static ReadyTask ready(String taskId, AbortFacts facts = AbortFacts.none(), boolean returned = false) {
        new ReadyTask(new TaskRef(taskId), facts, returned, false)
    }

    private static TrackerTask trackerTask(String taskId) {
        new TrackerTask(
                new TaskRef(taskId), new TaskSnapshot(taskId, 'title', 'body'),
                new TrackerTaskState.Ready(), AbortFacts.none(), false)
    }

    // Scenario: One task per run — three ready tasks, the oldest eligible one is claimed and
    // processed to a terminal result; only one claim call is made.
    def "queue of three ready tasks processes exactly the head and stops"() {
        given:
        tracker.listReady(_) >> [
            ready('PROJ-1'),
            ready('PROJ-2'),
            ready('PROJ-3')
        ]
        tracker.fetchTask(new TaskRef('PROJ-1')) >> trackerTask('PROJ-1')
        def bareAuto = newBareAuto()

        when:
        def result = bareAuto.run(cloneDir, pipeline(), RunArguments.InteractiveMode.ALL, tracker, INSTANCE)

        then:
        1 * tracker.claim(new TaskRef('PROJ-1'), INSTANCE.value()) >> new ClaimResult.Acquired()
        0 * tracker.claim(new TaskRef('PROJ-2'), _)
        0 * tracker.claim(new TaskRef('PROJ-3'), _)
        result instanceof TakeResult.Delivered
        gitRunner.run(cloneDir, 'rev-parse', '--verify', 'gnomish/PROJ-1').exitCode() == 0
    }

    // Scenario: Backoff hides a recently aborted task — the head is backed off (recent abort,
    // unexpired delay), so the next eligible entry is claimed instead.
    def "backed-off head is skipped in favor of the next eligible entry"() {
        given:
        def backedOffFacts = new AbortFacts(1, NOW - Duration.ofSeconds(30))
        tracker.listReady(_) >> [
            ready('PROJ-1', backedOffFacts),
            ready('PROJ-2')
        ]
        tracker.fetchTask(new TaskRef('PROJ-2')) >> trackerTask('PROJ-2')
        def bareAuto = newBareAuto()

        when:
        def result = bareAuto.run(cloneDir, pipeline(), RunArguments.InteractiveMode.ALL, tracker, INSTANCE)

        then:
        0 * tracker.claim(new TaskRef('PROJ-1'), _)
        1 * tracker.claim(new TaskRef('PROJ-2'), INSTANCE.value()) >> new ClaimResult.Acquired()
        result instanceof TakeResult.Delivered
    }

    // Scenario: Empty queue — listReady (post-filter) has nothing eligible; clean no-op
    // EmptyQueue, distinguishable at the type level for exit-code mapping (design D16, task 5.12).
    def "empty queue returns a clean no-op EmptyQueue"() {
        given:
        tracker.listReady(_) >> []
        def bareAuto = newBareAuto()

        when:
        def result = bareAuto.run(cloneDir, pipeline(), RunArguments.InteractiveMode.ALL, tracker, INSTANCE)

        then:
        result instanceof TakeResult.EmptyQueue
        0 * tracker.claim(*_)
    }

    // Scenario: all-backed-off queue behaves identically to a structurally empty one.
    def "queue with every entry backed off returns EmptyQueue"() {
        given:
        def backedOffFacts = new AbortFacts(1, NOW - Duration.ofSeconds(30))
        tracker.listReady(_) >> [
            ready('PROJ-1', backedOffFacts)
        ]
        def bareAuto = newBareAuto()

        when:
        def result = bareAuto.run(cloneDir, pipeline(), RunArguments.InteractiveMode.ALL, tracker, INSTANCE)

        then:
        result instanceof TakeResult.EmptyQueue
        0 * tracker.claim(*_)
    }

    // Scenario: Losing the claim race for the head falls through to the next eligible task —
    // genuine concurrent race, not a routine branch.
    def "claim race lost on the head falls through to the next eligible entry"() {
        given:
        tracker.listReady(_) >> [
            ready('PROJ-1'),
            ready('PROJ-2')
        ]
        tracker.fetchTask(new TaskRef('PROJ-2')) >> trackerTask('PROJ-2')
        def bareAuto = newBareAuto()

        when:
        def result = bareAuto.run(cloneDir, pipeline(), RunArguments.InteractiveMode.ALL, tracker, INSTANCE)

        then:
        1 * tracker.claim(new TaskRef('PROJ-1'), INSTANCE.value()) >> new ClaimResult.Held('gnomish-other-x1y2z3')
        1 * tracker.claim(new TaskRef('PROJ-2'), INSTANCE.value()) >> new ClaimResult.Acquired()
        result instanceof TakeResult.Delivered
    }

    // Scenario: every eligible entry loses its claim race — a real, if rare, terminal outcome:
    // nothing was processed, so bare take exits with a Skipped naming that every candidate raced.
    def "every eligible entry losing its claim race returns a terminal Skipped"() {
        given:
        tracker.listReady(_) >> [
            ready('PROJ-1'),
            ready('PROJ-2')
        ]
        def bareAuto = newBareAuto()

        when:
        def result = bareAuto.run(cloneDir, pipeline(), RunArguments.InteractiveMode.ALL, tracker, INSTANCE)

        then:
        1 * tracker.claim(new TaskRef('PROJ-1'), INSTANCE.value()) >> new ClaimResult.Held('gnomish-other-a1')
        1 * tracker.claim(new TaskRef('PROJ-2'), INSTANCE.value()) >> new ClaimResult.Held('gnomish-other-b2')
        result instanceof TakeResult.Skipped
        def reason = (result as TakeResult.Skipped).reason().toLowerCase()
        reason.contains('claimed') || reason.contains('race')
    }

    // Scenario: Returned task preferred — an older fresh task and a younger returned task are both
    // ready; the shared FeedPolicy orders the returned task first (FR6), so it is claimed even
    // though it is not the head of the adapter's queue order.
    def "returned task is claimed ahead of an older fresh task"() {
        given:
        tracker.listReady(_) >> [
            ready('PROJ-1'),
            ready('PROJ-2', AbortFacts.none(), true)
        ]
        tracker.fetchTask(new TaskRef('PROJ-2')) >> trackerTask('PROJ-2')
        def bareAuto = newBareAuto()

        when:
        def result = bareAuto.run(cloneDir, pipeline(), RunArguments.InteractiveMode.ALL, tracker, INSTANCE)

        then:
        1 * tracker.claim(new TaskRef('PROJ-2'), INSTANCE.value()) >> new ClaimResult.Acquired()
        0 * tracker.claim(new TaskRef('PROJ-1'), _)
        result instanceof TakeResult.Delivered
    }

    // Scenario: WIP limit blocks a fresh start — open fronts are at the configured limit and only
    // fresh tasks are ready; bare take exits as a clean no-op naming the WIP limit, distinct from a
    // structurally empty queue (EmptyQueue) and from a claim-race loss (Skipped naming the race).
    def "WIP limit blocks a fresh-only queue with a clean no-op naming the limit"() {
        given:
        tracker.listReady(_) >> [ready('PROJ-1')]
        openFronts = [null]
        def bareAuto = newBareAuto(1, headPick())

        when:
        def result = bareAuto.run(cloneDir, pipeline(), RunArguments.InteractiveMode.ALL, tracker, INSTANCE)

        then:
        0 * tracker.claim(*_)
        result instanceof TakeResult.Skipped
        def reason = (result as TakeResult.Skipped).reason().toLowerCase()
        reason.contains('wip') && reason.contains('limit')
    }

    // Scenario: the per-claim OpenFrontGate re-check (design D5) genuinely re-reads the open-front
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
        def result = bareAuto.run(cloneDir, pipeline(), RunArguments.InteractiveMode.ALL, tracker, INSTANCE)

        then:
        0 * tracker.claim(*_)
        result instanceof TakeResult.Skipped
    }

    // Scenario: a finished entry observed in listReady is declined before the walk resolves,
    // and never reaches the claim step (FR3, FR4, D4 of enforce-finish-terminality, task 4.2).
    def "a finished entry observed in the feed is declined and never claimed"() {
        given:
        tracker.listReady(_) >> [
            new ReadyTask(new TaskRef('PROJ-1'), AbortFacts.none(), false, true),
            ready('PROJ-2')
        ]
        tracker.fetchTask(new TaskRef('PROJ-2')) >> trackerTask('PROJ-2')
        def bareAuto = newBareAuto()

        when:
        def result = bareAuto.run(cloneDir, pipeline(), RunArguments.InteractiveMode.ALL, tracker, INSTANCE)

        then:
        1 * tracker.declineFinished(new TaskRef('PROJ-1'), _)
        0 * tracker.claim(new TaskRef('PROJ-1'), _)
        1 * tracker.claim(new TaskRef('PROJ-2'), INSTANCE.value()) >> new ClaimResult.Acquired()
        result instanceof TakeResult.Delivered
    }

    // Scenario: head-zone pick — a non-head candidate within the first K = 5 eligible entries can
    // be claimed first, proving strict FIFO is no longer enforced beyond the zone (FR9, D4); the
    // remaining candidates keep their original relative order for claim-race fallthrough.
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
        def result = bareAuto.run(cloneDir, pipeline(), RunArguments.InteractiveMode.ALL, tracker, INSTANCE)

        then:
        1 * tracker.claim(new TaskRef('PROJ-3'), INSTANCE.value()) >> new ClaimResult.Acquired()
        0 * tracker.claim(new TaskRef('PROJ-1'), _)
        0 * tracker.claim(new TaskRef('PROJ-2'), _)
        result instanceof TakeResult.Delivered
    }
}
