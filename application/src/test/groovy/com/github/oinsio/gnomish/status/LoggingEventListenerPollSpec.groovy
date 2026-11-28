package com.github.oinsio.gnomish.status

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.PendingSubject
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * NFR-O1, UX1 of migrate-verify-to-executor-contract: the lines {@link LoggingEventListener} renders
 * from a pending check's wait — one DEBUG line per {@link EngineEvent.PendingPolled} (once per poll of
 * a loop, so DEBUG by the logging rule's level table) naming the executor, the key's prefix, the
 * turn, the elapsed and the remaining time; one INFO line per {@link EngineEvent.PendingTimedOut}
 * naming the class the wait was resolved to and what was still awaited. The key prefix and the
 * details are the executor's text and leave through their log exit.
 */
class LoggingEventListenerPollSpec extends Specification {

    static final AttemptKey KEY = new AttemptKey('TASK-1', 'build', 0)
    static final CheckRef CHECK = new CheckRef(0, UntrustedText.manifest('executor:scm-status'))

    def listener = new LoggingEventListener()

    private static List<ILoggingEvent> capture(Closure<Void> emit) {
        LogCaptureSupport.capture(LoggingEventListener, Level.DEBUG, emit)
    }

    private static EngineEvent.PendingTimedOut timedOut(ExecutionResult.FailureClass failureClass, UntrustedText details) {
        new EngineEvent.PendingTimedOut(KEY, new PendingSubject.Check(CHECK), 'scm-status', UntrustedText.subprocess('k-1'), Duration.ofMinutes(10),
                new ExecutionResult.Failed(failureClass, UntrustedText.factory('timed out'), [], details))
    }

    // NFR-O1: each poll logs executor, key prefix, the turn, elapsed and remaining time
    def "NFR-O1: a poll logs one DEBUG line naming its executor, key prefix, turn, elapsed and remaining time"() {
        when:
        def lines = capture {
            listener.onEvent(new EngineEvent.PendingPolled(KEY, new PendingSubject.Check(CHECK), 'scm-status',
            UntrustedText.branchDocument('k-rotated'), PendingTurn.Origin.RECORDED, Duration.ofMinutes(2),
            Duration.ofMinutes(8)))
        }

        then:
        lines*.level == [Level.DEBUG]
        lines[0].formattedMessage ==
                'pending poll: executor=scm-status, key=k-rotated, turn=RECORDED, elapsed=PT2M, remaining=PT8M'
    }

    // NFR-O2 of extract-agent-round-runner: a pending transform round's poll logs the line a verdict
    //     poll does
    def "NFR-O2: a pending round's poll logs the same DEBUG line naming executor, key prefix, elapsed and remaining"() {
        when:
        def lines = capture {
            listener.onEvent(new EngineEvent.PendingPolled(KEY, PendingSubject.ROUND, 'remote-agent',
            UntrustedText.subprocess('job-42'), PendingTurn.Origin.ISSUED, Duration.ofMinutes(1),
            Duration.ofMinutes(59)))
        }

        then:
        lines*.level == [Level.DEBUG]
        lines[0].formattedMessage ==
                'pending poll: executor=remote-agent, key=job-42, turn=ISSUED, elapsed=PT1M, remaining=PT59M'
    }

    // NFR-O1: the key prefix is the executor's text, so it leaves through the log exit
    def "NFR-O1: a key prefix carrying a line break stays on the poll's one line"() {
        when:
        def lines = capture {
            listener.onEvent(new EngineEvent.PendingPolled(KEY, new PendingSubject.Check(CHECK), 'scm-status',
            UntrustedText.subprocess('k\nforged line'), PendingTurn.Origin.ISSUED, Duration.ofSeconds(30),
            Duration.ofSeconds(570)))
        }

        then:
        lines.size() == 1
        !lines[0].formattedMessage.contains('\n')
        lines[0].formattedMessage.contains('forged line')
    }

    // Scenario: verification/pending-poll / Timeout names the missing contexts
    def "UX1, NFR-O1: a timeout logs one INFO line naming the class and every expected context still absent"() {
        given:
        def absent = "waiting on 'lint', 'test' (absent)"

        when:
        def lines = capture {
            listener.onEvent(timedOut(ExecutionResult.FailureClass.INFRASTRUCTURE, UntrustedText.manifest(absent)))
        }

        then:
        lines*.level == [Level.INFO]
        lines[0].formattedMessage ==
                "pending poll timed out: executor=scm-status, key=k-1, elapsed=PT10M, class=INFRASTRUCTURE, awaiting=$absent"
                .toString()
    }

    // NFR-O1: a timeout whose wait had no reason says so
    def "NFR-O1: a timeout with no details logs its class and no awaited text"() {
        when:
        def lines = capture {
            listener.onEvent(timedOut(ExecutionResult.FailureClass.QUALITY, null))
        }

        then:
        lines*.formattedMessage ==
                [
                    'pending poll timed out: executor=scm-status, key=k-1, elapsed=PT10M, class=QUALITY, awaiting=-'
                ]
    }
}
