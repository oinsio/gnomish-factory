package com.github.oinsio.gnomish.domain.engine

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * VerifyOrchestrator adapter-throw handling, task 4.1 — a check adapter of any type
 * (builtin, command, external, judge) that throws is caught into a CannotVerify whose
 * details carry the exception's stack trace, and — being non-Pass — stops the chain so
 * later checks never run (FR4, NFR-O1). Implements FR4, NFR-O1 of add-stage-engine.
 */
class VerifyAdapterThrowSpec extends VerifyOrchestratorSpecBase {

    // FR4, NFR-O1: a builtin adapter throwing is caught into a CannotVerify whose details
    //     carry the exception's stack trace, and — being non-Pass — stops the chain
    def "catches a throwing builtin adapter into a CannotVerify carrying its stack trace"() {
        given: 'a builtin runner set to throw, then a command that would pass if reached'
        def builtinRunner = new ScriptedBuiltinCheckRunner()
        builtinRunner.toThrow = new IllegalStateException('builtin adapter kaboom')
        def commandRunner = new ScriptedCommandCheckRunner([new Verdict.Pass()])
        def checks = [
            builtin('files_exist'),
            command('./gradlew test')
        ]

        when: 'the chain is verified'
        def result = orchestrator(builtinRunner, commandRunner).verify(checks, CONTEXT, WORKSPACE, KEY)

        then: 'the check is a CannotVerify whose details hold the thrown stack trace'
        result.results.size() == 1
        def verdict = result.results[0].verdict
        verdict instanceof Verdict.CannotVerify
        verdict.reason() == 'check adapter threw'
        verdict.details().contains('IllegalStateException')
        verdict.details().contains('builtin adapter kaboom')

        and: 'the non-Pass verdict stopped the chain — the later command never ran'
        commandRunner.calls.isEmpty()
    }

    // FR4, NFR-O1: a command adapter throwing is caught into a CannotVerify with its trace
    def "catches a throwing command adapter into a CannotVerify carrying its stack trace"() {
        given: 'a command runner set to throw, then a builtin that would pass if reached'
        def builtinRunner = new ScriptedBuiltinCheckRunner([new Verdict.Pass()])
        def commandRunner = new ScriptedCommandCheckRunner()
        commandRunner.toThrow = new RuntimeException('command adapter kaboom')
        def checks = [
            command('./gradlew test'),
            builtin('files_exist')
        ]

        when: 'the chain is verified'
        def result = orchestrator(builtinRunner, commandRunner).verify(checks, CONTEXT, WORKSPACE, KEY)

        then: 'the check is a CannotVerify whose details hold the thrown stack trace'
        result.results.size() == 1
        def verdict = result.results[0].verdict
        verdict instanceof Verdict.CannotVerify
        verdict.reason() == 'check adapter threw'
        verdict.details().contains('command adapter kaboom')

        and: 'the chain stopped — the later builtin never ran'
        builtinRunner.calls.isEmpty()
    }

    // FR4, NFR-O1: an external adapter throwing from inside the poll loop is caught into a
    //     CannotVerify with its stack trace
    def "catches a throwing external adapter into a CannotVerify carrying its stack trace"() {
        given: 'an external client set to throw from poll, then a builtin that would pass'
        def externalClient = new ScriptedExternalCheckClient()
        externalClient.toThrow = new IllegalStateException('external adapter kaboom')
        def builtinRunner = new ScriptedBuiltinCheckRunner([new Verdict.Pass()])
        def commandRunner = new ScriptedCommandCheckRunner()
        def checks = [
            external('ci/build', Duration.ofSeconds(1), Duration.ofSeconds(3)),
            builtin('files_exist')
        ]

        when: 'the chain is verified'
        def result = orchestrator(builtinRunner, commandRunner, externalClient)
                .verify(checks, CONTEXT, WORKSPACE, KEY)

        then: 'the check is a CannotVerify whose details hold the thrown stack trace'
        result.results.size() == 1
        def verdict = result.results[0].verdict
        verdict instanceof Verdict.CannotVerify
        verdict.reason() == 'check adapter threw'
        verdict.details().contains('external adapter kaboom')

        and: 'the chain stopped — the later builtin never ran'
        builtinRunner.calls.isEmpty()
    }

    // FR4, NFR-O1: a judge adapter throwing (propagating up through JudgeVoting.vote) is
    //     caught into a CannotVerify with its stack trace
    def "catches a throwing judge adapter into a CannotVerify carrying its stack trace"() {
        given: 'a judge voter set to throw, then a builtin that would pass if reached'
        def judgeVoter = new ScriptedJudgeVoter()
        judgeVoter.toThrow = new RuntimeException('judge adapter kaboom')
        def builtinRunner = new ScriptedBuiltinCheckRunner([new Verdict.Pass()])
        def commandRunner = new ScriptedCommandCheckRunner()
        def checks = [
            judge(3),
            builtin('files_exist')
        ]

        when: 'the chain is verified'
        def result = orchestrator(builtinRunner, commandRunner,
                new ScriptedExternalCheckClient(), judgeVoter).verify(checks, CONTEXT, WORKSPACE, KEY)

        then: 'the check is a CannotVerify whose details hold the thrown stack trace'
        result.results.size() == 1
        def verdict = result.results[0].verdict
        verdict instanceof Verdict.CannotVerify
        verdict.reason() == 'check adapter threw'
        verdict.details().contains('judge adapter kaboom')

        and: 'the chain stopped — the later builtin never ran'
        builtinRunner.calls.isEmpty()
    }

    // NFR-O1: the caught adapter throw is logged at ERROR at the point of capture, naming the
    //     check — the delta-spec "Stack trace reaches the report" scenario's "an ERROR line is
    //     logged" clause, asserted via a Logback ListAppender on the VerifyOrchestrator logger.
    def "logs the caught adapter throw at ERROR at the point of capture"() {
        given: 'a ListAppender attached to the VerifyOrchestrator logger'
        Logger orchestratorLogger = (Logger) LoggerFactory.getLogger(VerifyOrchestrator)
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        orchestratorLogger.addAppender(appender)

        and: 'a builtin runner set to throw'
        def builtinRunner = new ScriptedBuiltinCheckRunner()
        builtinRunner.toThrow = new IllegalStateException('builtin adapter kaboom')

        when: 'the chain is verified'
        orchestrator(builtinRunner, new ScriptedCommandCheckRunner()).verify([builtin('files_exist')], CONTEXT, WORKSPACE, KEY)

        then: 'exactly one ERROR line was logged, naming the adapter throw'
        def errors = appender.list.findAll { it.level == Level.ERROR }
        errors.size() == 1
        errors[0].formattedMessage.contains('check adapter threw')

        cleanup:
        orchestratorLogger.detachAppender(appender)
    }
}
