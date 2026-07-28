package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
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
 * FR10, NFR-C1 of add-tracker-port (task 5.10): {@link TakeBareAuto#run} — bare auto {@code take}
 * takes the head of the {@code listReady} queue, applies abort backoff, claims exactly one task,
 * and exits; a lost claim race falls through to the next eligible entry; an empty post-filter
 * queue terminates with {@link TakeResult.EmptyQueue} (design D16: the clean cron no-op, exit 0),
 * while an all-raced queue — candidates existed but none could be claimed — terminates with
 * {@link TakeResult.Skipped} (a refusal-shaped outcome, exit 15). Mirrors the tracker-take spec's
 * "Bare auto mode takes the head of the queue" scenarios.
 */
class TakeBareAutoSpec extends TakeResumeSpecBase {

    private static final Duration BASE = Duration.ofMinutes(2)
    private static final Duration CAP = Duration.ofHours(1)
    private static final Instant NOW = Instant.parse('2026-07-23T12:00:00Z')
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)

    private TakeBareAuto newBareAuto() {
        def abortHandler = new AbortHandler(tracker, Clock.systemUTC())
        new TakeBareAuto(newAssembly(), worktreesRoot, abortHandler, ABORT_THRESHOLD, 'taskId', BASE, CAP, CLOCK, [])
    }

    private static ReadyTask ready(String taskId, AbortFacts facts = AbortFacts.none()) {
        new ReadyTask(new TaskRef(taskId), facts)
    }

    private static TrackerTask trackerTask(String taskId) {
        new TrackerTask(
                new TaskRef(taskId), new TaskSnapshot(taskId, 'title', 'body'),
                new TrackerTaskState.Ready(), AbortFacts.none())
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
}
