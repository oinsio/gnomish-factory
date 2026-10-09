package com.github.oinsio.gnomish.app.lease

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.logtext.RepeatSuppressor
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * {@link HeartbeatBeater} under a stop (FR12, NFR-O2 of fix-operator-blockers): a {@code
 * heartbeat} call that fails on a thread whose interrupt is set failed because the thread was told
 * to stop. The beat is unconfirmed — it reached no verdict — but it is not a beat failure: no WARN,
 * and no streak opened in the suppressor, so the beat-failed announcement stays owed to the first
 * genuine outage. A failure on an uninterrupted thread keeps its WARN.
 *
 * Implements FR12, NFR-O2 of fix-operator-blockers.
 */
class HeartbeatBeaterSpec extends Specification {

    private static final TaskRef A = new TaskRef('github:o/r#1')

    VirtualClock clock = new VirtualClock(Instant.parse('2026-09-28T10:00:00Z'))
    RepeatSuppressor suppressor = new RepeatSuppressor(clock, Duration.ofMinutes(30))
    Tracker tracker = Stub()
    HeartbeatBeater beater = new HeartbeatBeater(tracker, new HeartbeatProgress(), clock, suppressor)
    LogCaptureSupport logs = LogCaptureSupport.attach(HeartbeatBeater, Level.DEBUG)

    def cleanup() {
        logs.detach()
        Thread.interrupted()
    }

    // FR12, NFR-O2: the interrupted beat is unconfirmed, quiet, and leaves the interrupt set.
    def "a beat that fails on an interrupted thread is unconfirmed, with no warning and the interrupt kept"() {
        given:
        tracker.heartbeat(_, _) >> {
            Thread.currentThread().interrupt()
            throw new RuntimeException('call cancelled')
        }

        when:
        def outcome = beater.beat(A)

        then:
        outcome == BeatOutcome.UNCONFIRMED
        logs.list.every { !it.level.isGreaterOrEqual(Level.WARN) }
        Thread.currentThread().isInterrupted()
    }

    // FR12: no suppressor bookkeeping — the next genuine failure is still the first one, so it is
    //     the announced WARN rather than a quiet repeat of a streak the stop opened.
    def "an interrupted beat opens no failure streak"() {
        given:
        def interruptNext = true
        tracker.heartbeat(_, _) >> {
            if (interruptNext) {
                Thread.currentThread().interrupt()
            }
            throw new RuntimeException('5xx')
        }

        and: 'the stop arrives first'
        beater.beat(A)
        Thread.interrupted()
        interruptNext = false
        def beforeOutage = logs.list.size()

        when: 'a genuine outage follows'
        beater.beat(A)

        then: 'its failure is the announced first one'
        logs.list.drop(beforeOutage).any {
            it.level == Level.WARN && it.formattedMessage.startsWith(OperatorEvent.HEARTBEAT_BEAT_FAILED.head())
        }
    }

    // FR12, NFR-O2, control row: a failure on a thread nobody interrupted is a beat failure.
    def "a beat that fails on an uninterrupted thread keeps its beat-failed warning"() {
        given:
        tracker.heartbeat(_, _) >> { throw new RuntimeException('5xx') }

        when:
        def outcome = beater.beat(A)

        then:
        outcome == BeatOutcome.UNCONFIRMED
        logs.list.any {
            it.level == Level.WARN && it.formattedMessage.startsWith(OperatorEvent.HEARTBEAT_BEAT_FAILED.head())
        }
    }
}
