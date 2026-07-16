package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner

/**
 * VerifyOrchestrator chain ordering, task 4.1 — the verify chain runs in strict manifest
 * order and stops at the first non-Pass check so later checks never run (FR2). A CannotVerify
 * verdict stops the chain the same way, and an empty verify list passes vacuously.
 * Implements FR2 of add-stage-engine.
 */
class VerifyChainOrderSpec extends VerifyOrchestratorSpecBase {

    // FR2: an all-Pass chain runs every check in strict manifest order, one result each
    def "runs every check in manifest order when all pass"() {
        given: 'a builtin then a command check, both scripted to pass'
        def builtinRunner = new ScriptedBuiltinCheckRunner([new Verdict.Pass()])
        def commandRunner = new ScriptedCommandCheckRunner([new Verdict.Pass()])
        def checks = [
            builtin('files_exist'),
            command('./gradlew test')
        ]

        when: 'the chain is verified'
        def result = orchestrator(builtinRunner, commandRunner).verify(checks, CONTEXT, WORKSPACE, KEY)

        then: 'both runners ran exactly once, each once'
        builtinRunner.calls.size() == 1
        commandRunner.calls.size() == 1

        and: 'one CheckResult per check, in order, each carrying its runner verdict'
        result.results.size() == 2
        result.results[0].checkRef == CheckRef.of(0, checks[0])
        result.results[0].verdict instanceof Verdict.Pass
        result.results[1].checkRef == CheckRef.of(1, checks[1])
        result.results[1].verdict instanceof Verdict.Pass

        and: 'no judge ran, so judge usage is empty'
        result.judgeUsage.perVote().isEmpty()
    }

    // FR2: the first non-Pass check stops the chain — later checks are never invoked
    def "stops at the first non-Pass check and never invokes later checks"() {
        given: 'a builtin that fails, then a command that would pass if reached'
        def builtinRunner = new ScriptedBuiltinCheckRunner([new Verdict.Fail([])])
        def commandRunner = new ScriptedCommandCheckRunner([new Verdict.Pass()])
        def checks = [
            builtin('files_exist'),
            command('./gradlew test')
        ]

        when: 'the chain is verified'
        def result = orchestrator(builtinRunner, commandRunner).verify(checks, CONTEXT, WORKSPACE, KEY)

        then: 'the failing check ran but the later command check never did'
        builtinRunner.calls.size() == 1
        commandRunner.calls.isEmpty()

        and: 'only the failing check appears in the results'
        result.results.size() == 1
        result.results[0].checkRef == CheckRef.of(0, checks[0])
        result.results[0].verdict instanceof Verdict.Fail
    }

    // FR2: a CannotVerify verdict also stops the chain (any non-Pass breaks)
    def "stops the chain on a CannotVerify verdict too"() {
        given: 'a command that cannot verify, then a builtin that would pass'
        def builtinRunner = new ScriptedBuiltinCheckRunner([new Verdict.Pass()])
        def commandRunner = new ScriptedCommandCheckRunner([
            new Verdict.CannotVerify('binary not found', '')
        ])
        def checks = [
            command('missing-bin'),
            builtin('files_exist')
        ]

        when: 'the chain is verified'
        def result = orchestrator(builtinRunner, commandRunner).verify(checks, CONTEXT, WORKSPACE, KEY)

        then: 'the later builtin check is never invoked'
        commandRunner.calls.size() == 1
        builtinRunner.calls.isEmpty()

        and: 'only the CannotVerify check is recorded'
        result.results.size() == 1
        result.results[0].verdict instanceof Verdict.CannotVerify
    }

    // FR2: an empty verify list passes vacuously with an empty result and no events
    def "verifies an empty check list to an empty result"() {
        given: 'no checks at all'
        def builtinRunner = new ScriptedBuiltinCheckRunner()
        def commandRunner = new ScriptedCommandCheckRunner()

        when: 'the empty chain is verified'
        def result = orchestrator(builtinRunner, commandRunner).verify([], CONTEXT, WORKSPACE, KEY)

        then: 'no results, no judge usage, and no events'
        result.results.isEmpty()
        result.judgeUsage.perVote().isEmpty()
        listener.events.isEmpty()
    }
}
