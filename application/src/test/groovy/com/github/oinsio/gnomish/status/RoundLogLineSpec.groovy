package com.github.oinsio.gnomish.status

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Participants
import com.github.oinsio.gnomish.domain.engine.RoundExecutor
import com.github.oinsio.gnomish.logtext.MdcAwareThread
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * NFR-O2, UX3 of define-executor-contract: the round's log line names the executor, the protocol
 * version, the role, the status, the class and the bound, under the task's MDC — the engine's
 * listeners in their run order (MDC first, then the line), as the run assembly composes them.
 */
class RoundLogLineSpec extends Specification {

    static final AttemptKey KEY = new AttemptKey('TASK-7', 'lint', 1)

    def listeners = new CompositeEngineEventListener([
        new MdcEventListener(),
        new LoggingEventListener()
    ])

    def cleanup() {
        MdcEventListener.clearAttemptScope()
    }

    def lineOf(RoundExecutor executor, ExecutionResult.Status status) {
        def events = MdcAwareThread.taskScope(KEY.taskId()).withCloseable {
            LogCaptureSupport.capture(LoggingEventListener, Level.INFO) {
                listeners.onEvent(new EngineEvent.ExecutionFinished(KEY, Participants.transform('agent-cli', ExecutorUsage.none()), executor, status))
            }
        }
        assert events.size() == 1
        events[0]
    }

    // NFR-O2: a described (program) executor's round names name@version, protocol 1, role and status
    def "a described executor's round line names the executor, protocol version, role, status, class and bound under the task's MDC"() {
        given:
        def executor = new RoundExecutor('lint-bot', UntrustedText.subprocess('2.3.1'))
        def status = new ExecutionResult.Failed(ExecutionResult.FailureClass.LIMIT, UntrustedText.subprocess('turns 40'), [])

        when:
        def line = lineOf(executor, status)

        then:
        line.level == Level.INFO
        line.formattedMessage.contains('executor=lint-bot@2.3.1')
        line.formattedMessage.contains('protocolVersion=1')
        line.formattedMessage.contains('role=transform')
        line.formattedMessage.contains('status=failed')
        line.formattedMessage.contains('class=limit')
        line.formattedMessage.contains('bound=turns 40')
        line.MDCPropertyMap == [taskId: 'TASK-7', stage: 'lint', attempt: '1']
    }

    // NFR-O2: an executor with no describe has no version and no protocol; a completed round no class or bound
    def "an undescribed executor's completed round line spells the absent values as a dash"() {
        when:
        def line = lineOf(new RoundExecutor('agent-cli', null), new ExecutionResult.Completed())

        then:
        line.formattedMessage.contains('executor=agent-cli,')
        line.formattedMessage.contains('protocolVersion=-')
        line.formattedMessage.contains('status=completed')
        line.formattedMessage.contains('class=-')
        line.formattedMessage.contains('bound=-')
    }

    // NFR-O2, NFR-S2: the executor's own text leaves through its log exit — one line, no control bytes
    def "an executor version carrying control characters reaches the line neutralized"() {
        when:
        def line = lineOf(new RoundExecutor('lint-bot', UntrustedText.subprocess("2\u001b[31m\nforged")), new ExecutionResult.Completed())

        then:
        !line.formattedMessage.contains('\u001b')
        !line.formattedMessage.contains('\n')
    }

    // NFR-O2: a quality failure names its class and no bound
    def "a quality failure names its class and reaches no bound"() {
        when:
        def line = lineOf(new RoundExecutor('agent-cli', null),
                new ExecutionResult.Failed(ExecutionResult.FailureClass.QUALITY, UntrustedText.subprocess('red'), []))

        then:
        line.formattedMessage.contains('class=quality')
        line.formattedMessage.contains('bound=-')
    }
}
