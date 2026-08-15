package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import java.time.Duration

/**
 * VerifyOrchestrator events and timing, task 4.1 — the chain emits a CheckStarted/
 * CheckFinished pair per executed check, matching each check's CheckRef and carrying the
 * attempt key, and emits no events for a check the chain never reaches; each CheckResult
 * captures a non-null clock-measured duration (FR2). Implements FR2 of add-stage-engine.
 */
class VerifyEventsAndTimingSpec extends VerifyOrchestratorSpecBase {

    // FR2: a CheckStarted/CheckFinished pair is emitted per executed check, matching its CheckRef
    def "emits a CheckStarted and CheckFinished pair per executed check"() {
        given: 'a passing builtin followed by a failing command'
        def builtinRunner = new ScriptedBuiltinCheckRunner([new Verdict.Pass()])
        def commandRunner = new ScriptedCommandCheckRunner([new Verdict.Fail([])])
        def checks = [
            builtin('files_exist'),
            command('./gradlew test')
        ]

        when: 'the chain is verified'
        orchestrator(builtinRunner, commandRunner).verify(checks, CONTEXT, WORKSPACE, KEY)

        then: 'both checks ran, so four events fire in started/finished order'
        listener.events.size() == 4
        listener.events[0] instanceof EngineEvent.CheckStarted
        listener.events[1] instanceof EngineEvent.CheckFinished
        listener.events[2] instanceof EngineEvent.CheckStarted
        listener.events[3] instanceof EngineEvent.CheckFinished

        and: 'each event carries the key and the matching CheckRef'
        listener.events[0].key == KEY
        listener.events[0].check == CheckRef.of(0, checks[0])
        listener.events[1].result.checkRef == CheckRef.of(0, checks[0])
        listener.events[2].check == CheckRef.of(1, checks[1])
        listener.events[3].result.checkRef == CheckRef.of(1, checks[1])
    }

    // FR2: no events fire for the check after the chain stops
    def "emits no events for a check that never runs"() {
        given: 'a failing first check that stops the chain'
        def builtinRunner = new ScriptedBuiltinCheckRunner([new Verdict.Fail([])])
        def commandRunner = new ScriptedCommandCheckRunner([new Verdict.Pass()])
        def checks = [
            builtin('files_exist'),
            command('./gradlew test')
        ]

        when: 'the chain is verified'
        orchestrator(builtinRunner, commandRunner).verify(checks, CONTEXT, WORKSPACE, KEY)

        then: 'only the single executed check produced its two events'
        listener.events.size() == 2
        listener.events[0].check == CheckRef.of(0, checks[0])
        listener.events[1].result.checkRef == CheckRef.of(0, checks[0])
    }

    // FR2: each CheckResult captures a non-null clock-measured duration
    def "captures a per-check duration from the injected clock"() {
        given: 'a clock that does not advance during a passing check'
        def builtinRunner = new ScriptedBuiltinCheckRunner([new Verdict.Pass()])
        def commandRunner = new ScriptedCommandCheckRunner()
        def checks = [builtin('files_exist')]

        when: 'the chain is verified'
        def result = orchestrator(builtinRunner, commandRunner).verify(checks, CONTEXT, WORKSPACE, KEY)

        then: 'the duration is the zero the non-advancing clock implies, never null'
        result.results[0].duration() != null
        result.results[0].duration() == Duration.ZERO
    }
}
