package com.github.oinsio.gnomish.app.take

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.testfixtures.time.MovableClock
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FinishedDecline: the best-effort decline sweep over a {@code listReady} feed read, shared by
 * {@code serve}'s FeedCycle#poll and bare auto TakeBareAuto#run (design D4 of
 * enforce-finish-terminality).
 *
 * <p>Its announcement is latched (FR12 of harden-logging-observability): {@code serve} polls
 * every few seconds, so the same finished entry is observed over and over and must not be
 * announced over and over.
 *
 * Implements FR3, FR4, NFR-R2, NFR-R3, NFR-O1 of enforce-finish-terminality; FR12 of
 * harden-logging-observability.
 */
class FinishedDeclineSpec extends Specification {

    static final Duration ROLL_UP = Duration.ofMinutes(5)

    MovableClock clock = new MovableClock(Instant.parse('2026-08-31T10:00:00Z'))

    FinishedDecline decline = new FinishedDecline(new RepeatSuppressor(clock, ROLL_UP))

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
        decline.declineObserved(tracker, [
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

        and:
        def logs = LogCaptureSupport.attach(FinishedDecline)

        when:
        decline.declineObserved(tracker, [
            task('github:o/r#1', true),
            task('github:o/r#2', true)
        ])

        then:
        noExceptionThrown()
        attempted == [
            new TaskRef('github:o/r#1'),
            new TaskRef('github:o/r#2')
        ]

        and: 'FR15 of harden-logging-observability: the entry left for the next cycle is a coded WARN naming it'
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.DECLINE_FINISHED_FAILED.head())
        }
        event != null
        event.level == Level.WARN
        event.formattedMessage.contains('github:o/r#1')

        cleanup:
        logs.detach()
    }

    // FR12: serve re-reads the feed every few seconds. The first decline of a task is news; the
    //     repetitions are not, and a roll-up is what says the decline is not taking effect.
    def "FR12: a task declined on every poll is announced once, then counted"() {
        given:
        Tracker tracker = [declineFinished: { TaskRef ref, String message -> }] as Tracker
        def logs = LogCaptureSupport.attach(FinishedDecline, Level.DEBUG)

        when: 'five polls a minute apart still return the same finished entry'
        5.times {
            decline.declineObserved(tracker, [task('github:o/r#2', true)])
            clock.advance(Duration.ofMinutes(1))
        }

        then: 'one INFO announces it; the other four are diagnosis-only and carry the count'
        logs.list.findAll { it.level == Level.INFO }.size() == 1
        def repeats = logs.list.findAll { it.level == Level.DEBUG }
        repeats.size() == 4
        repeats.last().formattedMessage.contains('5x')

        cleanup:
        logs.detach()
    }

    def "FR12: a decline that keeps being needed past the quiet period says so"() {
        given:
        Tracker tracker = [declineFinished: { TaskRef ref, String message -> }] as Tracker
        def logs = LogCaptureSupport.attach(FinishedDecline)

        when:
        decline.declineObserved(tracker, [task('github:o/r#2', true)])
        clock.advance(ROLL_UP)
        decline.declineObserved(tracker, [task('github:o/r#2', true)])

        then: 'the roll-up names the count and says the decline is not taking effect'
        def announcements = logs.list.findAll { it.level == Level.INFO }
        announcements.size() == 2
        announcements[1].formattedMessage.contains('2 declines')
        announcements[1].formattedMessage.contains('not taking effect')

        cleanup:
        logs.detach()
    }

    def "FR12: two different finished tasks are each their own news"() {
        given:
        Tracker tracker = [declineFinished: { TaskRef ref, String message -> }] as Tracker
        def logs = LogCaptureSupport.attach(FinishedDecline)

        when:
        decline.declineObserved(tracker, [
            task('github:o/r#1', true),
            task('github:o/r#2', true)
        ])

        then: 'the latch is per task, not per sweep'
        logs.list.findAll { it.level == Level.INFO }.size() == 2

        cleanup:
        logs.detach()
    }

    // FR12: the latch's only eviction is a recovery nothing would ever report, so a decliner
    //     that lives as long as the serve daemon must release a task when it leaves the feed —
    //     otherwise one entry per finished task ever seen accumulates for the daemon's whole life.
    def "a task that leaves the feed is released from the latch"() {
        given:
        Tracker tracker = [declineFinished: { TaskRef ref, String message -> }] as Tracker

        when: 'a hundred distinct tasks are each declined once and then gone from the next read'
        (1..100).each { n ->
            decline.declineObserved(tracker, [task("github:o/r#${n}", true)])
        }
        decline.declineObserved(tracker, [])

        then: 'the latch holds none of them'
        latchedTasks().isEmpty()
    }

    // FR12: releasing must not re-arm the announcement while the task is still being handed back —
    //     a latch that forgot mid-streak would write the first-sighting INFO on every poll.
    def "a task still in the feed keeps its latch across sweeps"() {
        given:
        Tracker tracker = [declineFinished: { TaskRef ref, String message -> }] as Tracker
        def logs = LogCaptureSupport.attach(FinishedDecline)

        when: 'one task stays finished while another comes and goes around it'
        decline.declineObserved(tracker, [
            task('github:o/r#1', true),
            task('github:o/r#2', true)
        ])
        clock.advance(Duration.ofMinutes(1))
        decline.declineObserved(tracker, [task('github:o/r#1', true)])

        then: 'the surviving entry was announced once, and only the departed one was released'
        logs.list.findAll { it.level == Level.INFO }.size() == 2
        latchedTasks() == ['github:o/r#1'] as Set

        cleanup:
        logs.detach()
    }

    // FR12: a decline that stuck on the first write is the ordinary case and says nothing more;
    //     one the operator was told is "not taking effect" gets the line that retires that report.
    def "only a decline that was reported as not taking effect announces its clearing"() {
        given:
        Tracker tracker = [declineFinished: { TaskRef ref, String message -> }] as Tracker
        def logs = LogCaptureSupport.attach(FinishedDecline)

        when: 'one task clears immediately; the other is handed back past the quiet period first'
        decline.declineObserved(tracker, [
            task('github:o/r#1', true),
            task('github:o/r#2', true)
        ])
        clock.advance(ROLL_UP)
        decline.declineObserved(tracker, [task('github:o/r#2', true)])
        decline.declineObserved(tracker, [])

        then: 'only the stubborn one is reported as finally cleared'
        def cleared = logs.list.findAll {
            it.formattedMessage.contains('the decline took effect')
        }
        cleared.size() == 1
        cleared[0].formattedMessage.contains('github:o/r#2')
        cleared[0].formattedMessage.contains('2 declines')

        cleanup:
        logs.detach()
    }

    /** The latch's key set, which the suppressor does not expose — read off the decliner's field. */
    private Set<String> latchedTasks() {
        def field = FinishedDecline.getDeclaredField('latched')
        field.setAccessible(true)
        new HashSet<String>(field.get(decline) as Set<String>)
    }

    // FR3: an empty feed and an all-non-finished feed are both safe no-ops — no decline calls made.
    def "makes no decline calls when nothing observed is finished"() {
        given:
        def declined = []
        Tracker tracker = [
            declineFinished: { TaskRef ref, String message -> declined << ref },
        ] as Tracker

        when:
        decline.declineObserved(tracker, [task('github:o/r#1', false)])

        then:
        declined.isEmpty()
    }
}
