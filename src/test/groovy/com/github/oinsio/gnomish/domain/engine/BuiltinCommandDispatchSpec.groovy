package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import java.time.Duration

/**
 * VerifyOrchestrator builtin/command dispatch, task 4.1 — a Builtin dispatches to the
 * builtin runner and a Command to the command runner (never the other), each passing its
 * verdict through verbatim and receiving the chain's workspace unchanged; per-check
 * durations are measured from the injected clock across each runner call (FR3). Implements
 * FR3 of add-stage-engine.
 */
class BuiltinCommandDispatchSpec extends VerifyOrchestratorSpecBase {

    // FR3: a Builtin dispatches to the builtin runner (never the command runner) and its
    //      exact returned verdict is what the CheckResult carries
    def "dispatches a Builtin to the builtin runner and passes its verdict through verbatim"() {
        given: 'a builtin runner returning a specific Fail verdict, and an untouched command runner'
        def verdict = new Verdict.Fail([
            new Finding('boom', null, null)
        ])
        def builtinRunner = new ScriptedBuiltinCheckRunner([verdict])
        def commandRunner = new ScriptedCommandCheckRunner()
        def check = builtin('files_exist')

        when: 'the single builtin check is verified'
        def result = orchestrator(builtinRunner, commandRunner).verify([check], CONTEXT, WORKSPACE, KEY)

        then: 'only the builtin runner was invoked, with that exact check'
        builtinRunner.calls == [check]
        commandRunner.calls.isEmpty()

        and: 'the CheckResult carries the very verdict the runner returned'
        result.results[0].verdict().is(verdict)
    }

    // FR3: a Command dispatches to the command runner (never the builtin runner) and its
    //      exact returned verdict is what the CheckResult carries
    def "dispatches a Command to the command runner and passes its verdict through verbatim"() {
        given: 'a command runner returning a specific CannotVerify, and an untouched builtin runner'
        def verdict = new Verdict.CannotVerify('binary not found', 'trace')
        def builtinRunner = new ScriptedBuiltinCheckRunner()
        def commandRunner = new ScriptedCommandCheckRunner([verdict])
        def check = command('./gradlew test')

        when: 'the single command check is verified'
        def result = orchestrator(builtinRunner, commandRunner).verify([check], CONTEXT, WORKSPACE, KEY)

        then: 'only the command runner was invoked, with that exact check'
        commandRunner.calls == [check]
        builtinRunner.calls.isEmpty()

        and: 'the CheckResult carries the very verdict the runner returned'
        result.results[0].verdict().is(verdict)
    }

    // FR3: each runner receives the workspace the chain was given, threaded through unchanged
    def "passes the workspace through to whichever runner the check dispatches to"() {
        given: 'a passing builtin then a passing command, each recording its workspace'
        def seen = []
        def builtinRunner = new ScriptedBuiltinCheckRunner([new Verdict.Pass()])
        builtinRunner.onRun = { check, ws -> seen << ws }
        def commandRunner = new ScriptedCommandCheckRunner([new Verdict.Pass()])
        commandRunner.onRun = { check, ws -> seen << ws }
        def checks = [
            builtin('files_exist'),
            command('./gradlew test')
        ]

        when: 'the chain is verified'
        orchestrator(builtinRunner, commandRunner).verify(checks, CONTEXT, WORKSPACE, KEY)

        then: 'both runners received the exact WORKSPACE instance the chain was given'
        seen.size() == 2
        seen.every { it.is(WORKSPACE) }
    }

    // FR3: a builtin check's duration is measured from the injected clock across its runner call
    def "captures a builtin check duration measured across the runner call"() {
        given: 'a builtin runner whose call advances the clock by a known amount'
        def builtinRunner = new ScriptedBuiltinCheckRunner([new Verdict.Pass()])
        builtinRunner.onRun = { check, ws -> clock.advance(Duration.ofSeconds(5)) }
        def commandRunner = new ScriptedCommandCheckRunner()

        when: 'the single builtin check is verified'
        def result = orchestrator(builtinRunner, commandRunner).verify([builtin('files_exist')], CONTEXT, WORKSPACE, KEY)

        then: 'the recorded duration is the exact clock advance during the call'
        result.results[0].duration() == Duration.ofSeconds(5)
    }

    // FR3: a command check's duration is measured from the injected clock across its runner call
    def "captures a command check duration measured across the runner call"() {
        given: 'a command runner whose call advances the clock by a known amount'
        def builtinRunner = new ScriptedBuiltinCheckRunner()
        def commandRunner = new ScriptedCommandCheckRunner([new Verdict.Pass()])
        commandRunner.onRun = { check, ws -> clock.advance(Duration.ofMillis(250)) }

        when: 'the single command check is verified'
        def result = orchestrator(builtinRunner, commandRunner).verify([command('./gradlew test')], CONTEXT, WORKSPACE, KEY)

        then: 'the recorded duration is the exact clock advance during the call'
        result.results[0].duration() == Duration.ofMillis(250)
    }

    // FR3: per-check durations are independent — each is measured around only its own runner call
    def "measures each check's duration independently across its own runner call"() {
        given: 'a builtin advancing 2s then a command advancing 3s'
        def builtinRunner = new ScriptedBuiltinCheckRunner([new Verdict.Pass()])
        builtinRunner.onRun = { check, ws -> clock.advance(Duration.ofSeconds(2)) }
        def commandRunner = new ScriptedCommandCheckRunner([new Verdict.Pass()])
        commandRunner.onRun = { check, ws -> clock.advance(Duration.ofSeconds(3)) }
        def checks = [
            builtin('files_exist'),
            command('./gradlew test')
        ]

        when: 'the chain is verified'
        def result = orchestrator(builtinRunner, commandRunner).verify(checks, CONTEXT, WORKSPACE, KEY)

        then: 'each result carries only its own call duration, not the cumulative time'
        result.results[0].duration() == Duration.ofSeconds(2)
        result.results[1].duration() == Duration.ofSeconds(3)
    }
}
