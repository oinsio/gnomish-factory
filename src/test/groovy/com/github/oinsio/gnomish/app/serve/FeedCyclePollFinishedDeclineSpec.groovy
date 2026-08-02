package com.github.oinsio.gnomish.app.serve

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.fake.BudgetedVirtualSleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import spock.lang.Specification

/**
 * FeedCycle#poll's decline sweep over observed {@code finished} entries (design D4 of
 * enforce-finish-terminality, task 4.2): declines each {@code finished} entry it sees before
 * candidate selection, best-effort, and never lets a finished entry distort the WIP/open-front
 * accounting {@link com.github.oinsio.gnomish.app.take.FeedPolicy} uses for the rest.
 *
 * Implements FR3, FR4, NFR-R2, NFR-R3, NFR-O1 of enforce-finish-terminality.
 */
class FeedCyclePollFinishedDeclineSpec extends Specification {

    private static final InstanceId INSTANCE = InstanceId.generate('gnome')
    private static final Duration BASE = Duration.ofMinutes(2)
    private static final Duration CAP = Duration.ofHours(1)
    private static final Instant NOW = Instant.parse('2026-08-02T12:00:00Z')

    private static ReadyTask task(String id, boolean finished, boolean returned = false) {
        new ReadyTask(new TaskRef(id), AbortFacts.none(), returned, finished)
    }

    private static FeedCycle cycle(Tracker tracker, int wipLimit = 2) {
        def sleeper = new BudgetedVirtualSleeper(new VirtualClock())
        def outageRetry = new FeedOutageRetry(sleeper, { Duration.ofSeconds(1) })
        new FeedCycle(
                tracker, INSTANCE, new SlotLedger(1), { TaskRef ref -> } as SlotRunner,
                BASE, CAP, wipLimit, new Random(0), new FeedStateLogger(), outageRetry)
    }

    // FR3, FR4: a finished entry observed in one listReady result is declined exactly once within
    //     that same poll, independent of candidate selection.
    def "poll declines a finished entry observed in the feed within one poll"() {
        given:
        def declined = new CopyOnWriteArrayList<TaskRef>()
        Tracker tracker = [
            listReady      : { int limit -> [task('github:o/r#1', true)] },
            listOpen       : { -> [] },
            declineFinished: { TaskRef ref, String message -> declined << ref },
        ] as Tracker

        when:
        def poll = cycle(tracker).poll(NOW)

        then:
        declined == [new TaskRef('github:o/r#1')]
        poll.candidates().isEmpty()
    }

    // NFR-R2, NFR-R3: a declineFinished call that throws is caught and logged, not propagated —
    //     the poll still completes normally and other finished entries are still declined
    //     ("decline-failure convergence": the failed one is simply left for the next poll).
    def "a declineFinished failure does not stop the poll or block other finished entries from being declined"() {
        given:
        def attempted = new CopyOnWriteArrayList<TaskRef>()
        Tracker tracker = [
            listReady      : { int limit ->
                [
                    task('github:o/r#1', true),
                    task('github:o/r#2', true)
                ]
            },
            listOpen       : { -> [] },
            declineFinished: { TaskRef ref, String message ->
                attempted << ref
                if (ref == new TaskRef('github:o/r#1')) {
                    throw new RuntimeException('tracker down')
                }
            },
        ] as Tracker

        when:
        def poll = cycle(tracker).poll(NOW)

        then:
        noExceptionThrown()
        attempted == [
            new TaskRef('github:o/r#1'),
            new TaskRef('github:o/r#2')
        ]
        poll.candidates().isEmpty()
    }

    // NFR-R2, NFR-R3: a decline that fails on one poll is retried on the NEXT poll — because the
    //     status was never restored, the entry stays finished in the feed, and statelessness (no
    //     instance-local "already declined" memory) means the following cycle simply observes it
    //     again and declines it, converging without operator action.
    def "a decline that fails on one poll is retried and succeeds on the next poll"() {
        given:
        def attempts = new CopyOnWriteArrayList<TaskRef>()
        def declineCount = new AtomicInteger(0)
        Tracker tracker = [
            listReady      : { int limit -> [task('github:o/r#1', true)] },
            listOpen       : { -> [] },
            declineFinished: { TaskRef ref, String message ->
                attempts << ref
                if (declineCount.getAndIncrement() == 0) {
                    throw new RuntimeException('tracker down')
                }
            },
        ] as Tracker
        def feedCycle = cycle(tracker)

        when: 'the first poll declines and fails, then a second poll re-observes the still-finished entry'
        def poll1 = feedCycle.poll(NOW)
        def poll2 = feedCycle.poll(NOW)

        then: 'both polls attempted the decline, neither propagated the failure, and nothing was claimed'
        attempts == [
            new TaskRef('github:o/r#1'),
            new TaskRef('github:o/r#1')
        ]
        poll1.candidates().isEmpty()
        poll2.candidates().isEmpty()
    }

    // FR3, NFR-R1: a finished entry present in the feed alongside open fronts does not distort
    //     WIP/open-front accounting — the fresh candidate is still gated purely by the real
    //     openFrontCount (from listOpen), never by the finished entry's presence in listReady.
    def "a finished entry in the feed does not distort WIP accounting for the remaining candidates"() {
        given:
        def declined = new CopyOnWriteArrayList<TaskRef>()
        Tracker tracker = [
            listReady      : { int limit ->
                [
                    task('github:o/r#finished', true),
                    task('github:o/r#fresh', false)
                ]
            },
            listOpen       : { -> [] },
            declineFinished: { TaskRef ref, String message -> declined << ref },
            claim          : { TaskRef ref, String instance -> new ClaimResult.Acquired() },
        ] as Tracker

        when:
        def poll = cycle(tracker, 1).poll(NOW)

        then: 'the finished entry was declined and excluded, and the fresh entry alone made it through as a candidate'
        declined == [
            new TaskRef('github:o/r#finished')
        ]
        poll.openFrontCount() == 0
        poll.candidates().collect { it.ref() } == [
            new TaskRef('github:o/r#fresh')
        ]
    }
}
