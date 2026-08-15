package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import spock.lang.Specification

/**
 * FinishedDecline: the best-effort decline sweep over a {@code listReady} feed read, shared by
 * {@code serve}'s FeedCycle#poll and bare auto TakeBareAuto#run (design D4 of
 * enforce-finish-terminality).
 *
 * Implements FR3, FR4, NFR-R2, NFR-R3, NFR-O1 of enforce-finish-terminality.
 */
class FinishedDeclineSpec extends Specification {

    private static ReadyTask task(String id, boolean finished) {
        new ReadyTask(new TaskRef(id), AbortFacts.none(), false, finished, 'fixture title')
    }

    // FR3, FR4: only finished entries trigger a decline call; non-finished entries are never
    //     declined, proving the sweep does not overreach into candidates FeedPolicy still needs.
    def "declines only the finished entries, leaving non-finished entries untouched"() {
        given:
        def declined = []
        Tracker tracker = [
            declineFinished: { TaskRef ref, String message -> declined << ref },
        ] as Tracker

        when:
        FinishedDecline.declineObserved(tracker, [
            task('github:o/r#1', false),
            task('github:o/r#2', true),
            task('github:o/r#3', false)
        ])

        then:
        declined == [new TaskRef('github:o/r#2')]
    }

    // NFR-R2, NFR-R3: a thrown exception from one entry's decline is caught and logged, not
    //     propagated, and does not stop the sweep from attempting the remaining finished entries —
    //     the "decline-failure convergence" behavior the next poll cycle relies on.
    def "a thrown decline for one entry does not stop the sweep from attempting the rest"() {
        given:
        def attempted = []
        Tracker tracker = [
            declineFinished: { TaskRef ref, String message ->
                attempted << ref
                if (ref == new TaskRef('github:o/r#1')) {
                    throw new RuntimeException('tracker down')
                }
            },
        ] as Tracker

        when:
        FinishedDecline.declineObserved(tracker, [
            task('github:o/r#1', true),
            task('github:o/r#2', true)
        ])

        then:
        noExceptionThrown()
        attempted == [
            new TaskRef('github:o/r#1'),
            new TaskRef('github:o/r#2')
        ]
    }

    // FR3: an empty feed and an all-non-finished feed are both safe no-ops — no decline calls made.
    def "makes no decline calls when nothing observed is finished"() {
        given:
        def declined = []
        Tracker tracker = [
            declineFinished: { TaskRef ref, String message -> declined << ref },
        ] as Tracker

        when:
        FinishedDecline.declineObserved(tracker, [task('github:o/r#1', false)])

        then:
        declined.isEmpty()
    }
}
