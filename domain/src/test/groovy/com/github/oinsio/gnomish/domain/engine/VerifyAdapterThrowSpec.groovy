package com.github.oinsio.gnomish.domain.engine

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.time.Duration

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
        verdict.reason().forLog() == 'check adapter threw'
        verdict.details().forConsole().contains('IllegalStateException')
        verdict.details().forConsole().contains('builtin adapter kaboom')

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
        verdict.reason().forLog() == 'check adapter threw'
        verdict.details().forConsole().contains('command adapter kaboom')

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
        verdict.reason().forLog() == 'check adapter threw'
        verdict.details().forConsole().contains('external adapter kaboom')

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
        verdict.reason().forLog() == 'check adapter threw'
        verdict.details().forConsole().contains('judge adapter kaboom')

        and: 'the chain stopped — the later builtin never ran'
        builtinRunner.calls.isEmpty()
    }

    // NFR-O1: the caught adapter throw is logged at ERROR at the point of capture, naming the
    //     check — the delta-spec "Stack trace reaches the report" scenario's "an ERROR line is
    //     logged" clause, asserted via a capture on the VerifyOrchestrator logger.
    def "logs the caught adapter throw at ERROR at the point of capture"() {
        given: 'a capture on the VerifyOrchestrator logger'
        def logs = LogCaptureSupport.attach(VerifyOrchestrator)

        and: 'a builtin runner set to throw'
        def builtinRunner = new ScriptedBuiltinCheckRunner()
        builtinRunner.toThrow = new IllegalStateException('builtin adapter kaboom')

        when: 'the chain is verified'
        orchestrator(builtinRunner, new ScriptedCommandCheckRunner()).verify([builtin('files_exist')], CONTEXT, WORKSPACE, KEY)

        then: 'exactly one ERROR line was logged, naming the adapter throw'
        def errors = logs.list.findAll { it.level == Level.ERROR }
        errors.size() == 1
        errors[0].formattedMessage.contains('check adapter threw')
        errors[0].formattedMessage.startsWith(OperatorEvent.CHECK_ADAPTER_THREW.head())

        cleanup:
        logs.detach()
    }
}
