package com.github.oinsio.gnomish.app.take

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * {@link FinishedDecline} under a stop (FR12, NFR-O2 of fix-operator-blockers): a decline that
 * fails on a thread whose interrupt is set failed because the feed was told to stop. That is not
 * a decline failure to warn about, and the sweep makes no further tracker call — the remaining
 * finished entries are left for the next process. A failure on an uninterrupted thread keeps its
 * WARN and the sweep goes on (NFR-R2 of enforce-finish-terminality).
 *
 * Implements FR12, NFR-O2 of fix-operator-blockers.
 */
class FinishedDeclineInterruptSpec extends Specification {

    private static final TaskRef FIRST = new TaskRef('github:o/r#1')
    private static final TaskRef SECOND = new TaskRef('github:o/r#2')

    FinishedDecline decline = new FinishedDecline(
    new RepeatSuppressor(new VirtualClock(Instant.parse('2026-09-28T10:00:00Z')), Duration.ofMinutes(5)))
    LogCaptureSupport logs = LogCaptureSupport.attach(FinishedDecline)

    def cleanup() {
        logs.detach()
        Thread.interrupted()
    }

    private static ReadyTask finished(TaskRef ref) {
        new ReadyTask(ref, AbortFacts.none(), false, true, UntrustedText.tracker('fixture title'))
    }

    private static Tracker failingFirst(List<TaskRef> attempted, boolean interrupt) {
        [
            declineFinished: { TaskRef ref, String message ->
                attempted << ref
                if (ref == FIRST) {
                    if (interrupt) {
                        Thread.currentThread().interrupt()
                    }
                    throw new RuntimeException('call failed')
                }
            },
        ] as Tracker
    }

    // FR12, NFR-O2: the stop ends the sweep quietly; the second decline is never attempted.
    def "a decline that fails on an interrupted thread stops the sweep with no warning"() {
        given:
        def attempted = []

        when:
        decline.declineObserved(failingFirst(attempted, true), [
            finished(FIRST),
            finished(SECOND)
        ])

        then:
        attempted == [FIRST]
        logs.list.every { !it.level.isGreaterOrEqual(Level.WARN) }
        Thread.currentThread().isInterrupted()
    }

    // FR12, control row: a failure nobody's stop caused is warned about, and the sweep goes on.
    def "a decline that fails on an uninterrupted thread is warned about and the sweep goes on"() {
        given:
        def attempted = []

        when:
        decline.declineObserved(failingFirst(attempted, false), [
            finished(FIRST),
            finished(SECOND)
        ])

        then:
        attempted == [FIRST, SECOND]
        logs.list.any {
            it.level == Level.WARN && it.formattedMessage.startsWith(OperatorEvent.DECLINE_FINISHED_FAILED.head())
        }
    }
}
