package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR7 of define-executor-contract (task 7.4): the {@code failed/infrastructure} result an agent
 * round that ended without its result reports — its reason, what it spent, its denials — in place
 * of the thrown wrapper it replaced. FR20 of fix-terminal-receipt-convergence: the stderr tail
 * rides the reason.
 */
class AgentRoundFailureSpec extends Specification {

    static final AttemptKey KEY = new AttemptKey('TASK-1', 'build', 2)

    static final Denial DENIAL = Denial.unidentified(new Finding('egress denied', 'paste.example', null))

    // FR7: the contract status, carrying the wall time and the denials, nothing else
    def "FR7: the result is failed/infrastructure with the round's wall time, its denials and an empty trace"() {
        when:
        def result = AgentRoundFailure.of(KEY, new IllegalStateException('boom'), UntrustedText.agent(''),
                Duration.ofSeconds(7), [DENIAL])

        then:
        def failed = InfrastructureResults.of(result)
        failed.findings().isEmpty()
        result.usage().wallTime() == Duration.ofSeconds(7)
        result.usage().tools().isEmpty()
        result.usage().byModel().isEmpty()
        result.denials() == [DENIAL]
        result.trace().key() == KEY
        result.trace().calls().isEmpty()
    }

    // FR7: the reason names the failure, then every cause under it
    def "FR7: the reason names the failure and its causes, as subprocess text"() {
        given:
        def failure = new IllegalStateException('agent CLI process failed to start: claude',
                new IOException('no such binary'))

        when:
        def reason = AgentRoundFailure.reason(failure, UntrustedText.agent(''))

        then:
        reason.provenance() == Provenance.SUBPROCESS
        reason.forLog() == 'agent-cli round ended without its result: ' + failure +
                '; caused by: ' + failure.cause
        !reason.contains('stderr')
    }

    // FR20: the tail follows the failure through its log exit
    def "FR20: a non-blank stderr tail follows the failure under its label"() {
        given:
        def failure = new RuntimeException('round exceeded roundTimeout')
        def tail = UntrustedText.agent('error: not logged in')

        expect:
        AgentRoundFailure.reason(failure, tail).forLog() ==
                'agent-cli round ended without its result: ' + failure + '; ' +
                AgentRoundFailure.STDERR_TAIL_LABEL + tail.forLog()
    }

    // A cyclic cause chain is named a bounded number of times, never looped forever
    def "a cyclic cause chain names at most MAX_CAUSES causes"() {
        given:
        def first = new RuntimeException('first')
        def second = new RuntimeException('second')
        first.initCause(second)
        second.initCause(first)

        when:
        def reason = AgentRoundFailure.reason(first, UntrustedText.agent('')).forLog()

        then:
        reason.count('caused by: ') == AgentRoundFailure.MAX_CAUSES
    }

    // The wall time is the clock's difference, never negative
    def "elapsed is #expected from #from to #to"() {
        expect:
        AgentRoundFailure.elapsed(Instant.ofEpochSecond(from), Instant.ofEpochSecond(to)) == expected

        where:
        from | to || expected
        10 | 52 || Duration.ofSeconds(42)
        10 | 10 || Duration.ZERO
        10 | 4 || Duration.ZERO
    }

    // One failure, one log: the engine writes the operator line; this is one DEBUG line with the stack
    def "the failure is one DEBUG line carrying the throwable, and no operator line"() {
        given:
        def logs = LogCaptureSupport.attach(AgentRoundFailure, Level.DEBUG)
        def failure = new IllegalStateException('boom')

        when:
        AgentRoundFailure.of(KEY, failure, UntrustedText.agent(''), Duration.ZERO, [])

        then:
        logs.list*.level == [Level.DEBUG]
        logs.list[0].formattedMessage.contains(KEY.toString())
        logs.list[0].throwableProxy.message == 'boom'

        cleanup:
        logs.detach()
    }
}
