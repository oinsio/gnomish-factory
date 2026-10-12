package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration

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
 * of the queue" scenarios; the order the walk visits candidates in is {@link
 * TakeBareAutoFeedOrderSpec}'s.
 */
class TakeBareAutoSpec extends TakeBareAutoSpecBase {

    // One task per run — three ready tasks, the oldest eligible one is claimed and
    // processed to a terminal result; only one claim call is made.
    // Scenario: tracker/tracker-take / One task per run
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
        def result = bareAuto.run(new RunOrder(cloneDir, null, pipeline(), false), tracker, INSTANCE)

        then:
        1 * tracker.claim(new TaskRef('PROJ-1'), INSTANCE.value()) >> new ClaimResult.Acquired(new ClaimEpoch(1))
        0 * tracker.claim(new TaskRef('PROJ-2'), _)
        0 * tracker.claim(new TaskRef('PROJ-3'), _)
        result instanceof TakeResult.Delivered
        gitExitCode(cloneDir, 'rev-parse', '--verify', 'gnomish/PROJ-1') == 0
    }

    // Backoff hides a recently aborted task — the head is backed off (recent abort,
    // unexpired delay), so the next eligible entry is claimed instead.
    // Scenario: tracker/tracker-take / Backoff hides a task
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
        def result = bareAuto.run(new RunOrder(cloneDir, null, pipeline(), false), tracker, INSTANCE)

        then:
        0 * tracker.claim(new TaskRef('PROJ-1'), _)
        1 * tracker.claim(new TaskRef('PROJ-2'), INSTANCE.value()) >> new ClaimResult.Acquired(new ClaimEpoch(1))
        result instanceof TakeResult.Delivered
    }

    // Empty queue — listReady (post-filter) has nothing eligible; clean no-op
    // EmptyQueue, distinguishable at the type level for exit-code mapping (design D16, task 5.12).
    def "empty queue returns a clean no-op EmptyQueue"() {
        given:
        tracker.listReady(_) >> []
        def bareAuto = newBareAuto()

        when:
        def result = bareAuto.run(new RunOrder(cloneDir, null, pipeline(), false), tracker, INSTANCE)

        then:
        result instanceof TakeResult.EmptyQueue
        0 * tracker.claim(*_)
    }

    // All-backed-off queue behaves identically to a structurally empty one.
    def "queue with every entry backed off returns EmptyQueue"() {
        given:
        def backedOffFacts = new AbortFacts(1, NOW - Duration.ofSeconds(30))
        tracker.listReady(_) >> [
            ready('PROJ-1', backedOffFacts)
        ]
        def bareAuto = newBareAuto()

        when:
        def result = bareAuto.run(new RunOrder(cloneDir, null, pipeline(), false), tracker, INSTANCE)

        then:
        result instanceof TakeResult.EmptyQueue
        0 * tracker.claim(*_)
    }

    // Losing the claim race for the head falls through to the next eligible task —
    // genuine concurrent race, not a routine branch.
    // Scenario: tracker/tracker-take / A lost claim race falls through
    def "claim race lost on the head falls through to the next eligible entry"() {
        given:
        tracker.listReady(_) >> [
            ready('PROJ-1'),
            ready('PROJ-2')
        ]
        tracker.fetchTask(new TaskRef('PROJ-2')) >> trackerTask('PROJ-2')
        def bareAuto = newBareAuto()

        when:
        def result = bareAuto.run(new RunOrder(cloneDir, null, pipeline(), false), tracker, INSTANCE)

        then:
        1 * tracker.claim(new TaskRef('PROJ-1'), INSTANCE.value()) >> new ClaimResult.Held('gnomish-other-x1y2z3')
        1 * tracker.claim(new TaskRef('PROJ-2'), INSTANCE.value()) >> new ClaimResult.Acquired(new ClaimEpoch(1))
        result instanceof TakeResult.Delivered
    }

    // Every eligible entry loses its claim race — a real, if rare, terminal outcome:
    // nothing was processed, so bare take exits with a Skipped naming that every candidate raced.
    def "every eligible entry losing its claim race returns a terminal Skipped"() {
        given:
        tracker.listReady(_) >> [
            ready('PROJ-1'),
            ready('PROJ-2')
        ]
        def bareAuto = newBareAuto()

        when:
        def result = bareAuto.run(new RunOrder(cloneDir, null, pipeline(), false), tracker, INSTANCE)

        then:
        1 * tracker.claim(new TaskRef('PROJ-1'), INSTANCE.value()) >> new ClaimResult.Held('gnomish-other-a1')
        1 * tracker.claim(new TaskRef('PROJ-2'), INSTANCE.value()) >> new ClaimResult.Held('gnomish-other-b2')
        result instanceof TakeResult.Skipped
        def reason = (result as TakeResult.Skipped).reason().forLog().toLowerCase()
        reason.contains('claimed') || reason.contains('race')
    }

    // A finished entry observed in listReady is declined before the walk resolves,
    // and never reaches the claim step (FR3, FR4, D4 of enforce-finish-terminality, task 4.2).
    // Scenario: tracker/tracker-take / Finished task never claimed from the feed
    def "a finished entry observed in the feed is declined and never claimed"() {
        given:
        tracker.listReady(_) >> [
            new ReadyTask(new TaskRef('PROJ-1'), AbortFacts.none(), false, true, UntrustedText.tracker('fixture title')),
            ready('PROJ-2')
        ]
        tracker.fetchTask(new TaskRef('PROJ-2')) >> trackerTask('PROJ-2')
        def bareAuto = newBareAuto()

        when:
        def result = bareAuto.run(new RunOrder(cloneDir, null, pipeline(), false), tracker, INSTANCE)

        then:
        1 * tracker.declineFinished(new TaskRef('PROJ-1'), _)
        0 * tracker.claim(new TaskRef('PROJ-1'), _)
        1 * tracker.claim(new TaskRef('PROJ-2'), INSTANCE.value()) >> new ClaimResult.Acquired(new ClaimEpoch(1))
        result instanceof TakeResult.Delivered
    }
}
