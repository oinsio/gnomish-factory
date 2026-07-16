package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * StageAttemptLoop, task 5.3 — the quality-failure retry path. A round whose
 * verification ends in {@code Verdict.Fail} records the round burning ONE attempt
 * (attemptsUsed +1) and re-runs the SAME stage: the next executor request carries an
 * incremented attempt number and the failed check results of ALL prior attempts as
 * feedback (FR4, FR5 — the "Feedback carries the whole stage history" scenario).
 *
 * <p>To reach a stable, assertable terminal WITHOUT triggering the task 5.8 advance
 * stub (a passing round) or the task 5.5 exhaustion stub (limit reached), the attempt
 * limit is kept high (5) and the scripted sequence ends in a wired CannotVerify
 * terminal: Fail(findingA) → Fail(findingB) → CannotVerify. The two Fail rounds burn
 * attempts and retry; the CannotVerify round is the wired Escalated(CannotVerify)
 * terminal from task 5.2.
 *
 * <p>Implements FR4, FR5 of add-stage-engine.
 */
class QualityFailureSpec extends Specification {

    static final def WORKSPACE = new FakeWorkspace()
    static final def CONTEXT = new TaskContext('TASK-1', 'title', 'body', [])

    def executor = new ScriptedExecutor()
    def builtinRunner = new ScriptedBuiltinCheckRunner()
    def commandRunner = new ScriptedCommandCheckRunner()
    def externalClient = new ScriptedExternalCheckClient()
    def judgeVoter = new ScriptedJudgeVoter()
    def persistence = new InMemoryAttemptPersistence()
    def listener = new RecordingEventListener()
    def clock = new VirtualClock()
    def sleeper = new VirtualSleeper(clock)

    EnginePorts ports() {
        new EnginePorts(executor, builtinRunner, commandRunner, externalClient, judgeVoter,
                listener, persistence, clock, sleeper)
    }

    static VerifyCheck.Builtin builtin(String name) {
        new VerifyCheck.Builtin(name, [:])
    }

    static StageDefinition stage(String name, int attemptLimit, List<VerifyCheck> verify) {
        new StageDefinition(name, 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', verify, new AutonomyLimits(attemptLimit), AdvancementMode.AUTO)
    }

    static PipelineDefinition pipeline(StageDefinition... stages) {
        new PipelineDefinition('1', new AutonomyLimits(5), stages.toList())
    }

    static ExecutionResult.Completed completed() {
        new ExecutionResult.Completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []))
    }

    static Verdict.Fail fail(String message) {
        new Verdict.Fail([
            new Finding(message, null, null)
        ])
    }

    // FR5/FR4: a quality Fail round burns one attempt and re-runs the SAME stage — the
    //     executor is invoked again with the incremented attempt number.
    def "a quality failure burns one attempt and re-runs the stage with the next attempt"() {
        given: 'a stage whose check fails once then cannot verify, run by a Completed executor each round'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        builtinRunner.scripted << fail('findingA')
        builtinRunner.scripted << new Verdict.CannotVerify('binary not found', 'no such tool')
        executor.scripted << completed()
        executor.scripted << completed()

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the executor was invoked twice — the Fail round retried the same stage'
        executor.requests.size() == 2

        and: 'the retry request carries attempt 1 while the first carried attempt 0'
        executor.requests[0].attempt() == 0
        executor.requests[1].attempt() == 1

        and: 'the Fail round burned one attempt; the CannotVerify round did not'
        def finalState = outcome.finalState()
        finalState.attemptsUsed() == 1
        finalState.attempts().size() == 2

        and: 'the engine classified the burned round explicitly as QUALITY_FAILURE (FR13, D5)'
        // FR13: a Fail round records the QUALITY_FAILURE result classification; the terminal
        // CannotVerify round records CANNOT_VERIFY. Both are read from the recorded history.
        finalState.attempts()[0].result() == AttemptRecord.Result.QUALITY_FAILURE
        finalState.attempts()[1].result() == AttemptRecord.Result.CANNOT_VERIFY
    }

    // FR4 (feedback carries the whole stage history): after two quality failures, the third
    //     executor request's feedback contains the failed check results of BOTH prior attempts.
    def "the third executor request feedback carries the failed check results of both prior attempts"() {
        given: 'a stage that fails twice then cannot verify — two burned attempts before a wired terminal'
        def stageDef = stage('build', 5, [builtin('files_exist')])
        def failA = fail('findingA')
        def failB = fail('findingB')
        builtinRunner.scripted << failA
        builtinRunner.scripted << failB
        builtinRunner.scripted << new Verdict.CannotVerify('binary not found', 'no such tool')
        executor.scripted << completed()
        executor.scripted << completed()
        executor.scripted << completed()

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the run reached the wired CannotVerify terminal after three rounds'
        outcome instanceof TaskOutcome.Escalated
        outcome.report() instanceof EscalationReport.CannotVerify

        and: 'the executor was invoked three times, the last being attempt 2'
        executor.requests.size() == 3
        executor.requests[2].attempt() == 2

        and: 'attempt 1 (round 1) feedback carries only attempt 0\'s failed check result'
        def feedback1 = executor.requests[1].feedback()
        feedback1.size() == 1
        feedback1[0].verdict().is(failA)

        and: 'attempt 2 (round 2) feedback carries the failed check results of BOTH attempts 0 and 1, in round order'
        def feedback2 = executor.requests[2].feedback()
        feedback2.size() == 2
        feedback2[0].verdict().is(failA)
        feedback2[1].verdict().is(failB)

        and: 'two quality failures were burned; the CannotVerify round is recorded but unburned'
        def finalState = outcome.finalState()
        finalState.attemptsUsed() == 2
        finalState.attempts().size() == 3
    }
}
