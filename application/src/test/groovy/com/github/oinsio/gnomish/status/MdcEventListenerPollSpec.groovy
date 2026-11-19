package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import org.slf4j.MDC
import spock.lang.Specification

/**
 * NFR-O1 of migrate-verify-to-executor-contract: the events of a pending check's wait set the
 * {@code stage} and {@code attempt} MDC keys from their own key, like every other key-carrying
 * event, so the line {@link LoggingEventListener} renders from each is written under the check's
 * attempt even where nothing before it in the stream set them.
 */
class MdcEventListenerPollSpec extends Specification {

    static final CheckRef CHECK = new CheckRef(0, UntrustedText.manifest('executor:ci'))

    def cleanup() {
        MdcEventListener.clearAttemptScope()
    }

    // NFR-O1: each poll logs under the task's MDC
    def "NFR-O1: #description sets stage and attempt from its key"() {
        when:
        new MdcEventListener().onEvent(event)

        then:
        MDC.get('stage') == 'verify'
        MDC.get('attempt') == '3'

        where:
        description | event
        'a poll' | new EngineEvent.CheckPolled(new AttemptKey('TASK-1', 'verify', 3), CHECK, 'scm-status',
                UntrustedText.subprocess('k-1'), PendingTurn.Origin.ISSUED, Duration.ofSeconds(30), Duration.ofMinutes(9))
        'a timeout' | new EngineEvent.CheckTimedOut(new AttemptKey('TASK-1', 'verify', 3), CHECK, 'scm-status',
                UntrustedText.subprocess('k-1'), Duration.ofMinutes(10), new ExecutionResult.Failed(
                        ExecutionResult.FailureClass.QUALITY, UntrustedText.factory('timed out'), [], null))
    }
}
