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
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration
import spock.lang.Specification

/**
 * StageAttemptLoop, task 5.2 — one attempt round driven end to end through
 * Engine.run: execute the stage once, verify its checks, and record the round WITH
 * its executor/judge metrics, then classify the overall verdict (FR4, FR13). This
 * task wires only the CannotVerify terminal: a round whose verification cannot reach
 * a verdict is recorded but NOT counted (attemptsUsed unchanged) and escalates as
 * EscalationReport.CannotVerify naming the failing check. The Pass (advancement, task
 * 5.8) and Fail (quality-failure retry, task 5.3) arms are stubs not exercised here.
 * Implements FR4, FR13 of add-stage-engine.
 */
class StageAttemptLoopSpec extends Specification {

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

    static VerifyCheck.Judge judge(int votes) {
        new VerifyCheck.Judge('criteria.md', 'model', [:], votes)
    }

    static StageDefinition stage(String name, int attemptLimit, List<VerifyCheck> verify) {
        new StageDefinition(name, 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', verify, new AutonomyLimits(attemptLimit), AdvancementMode.AUTO)
    }

    static PipelineDefinition pipeline(StageDefinition... stages) {
        new PipelineDefinition('1', new AutonomyLimits(3), stages.toList())
    }

    static ExecutionResult.Completed completed(ExecutorUsage usage) {
        new ExecutionResult.Completed(usage, new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []))
    }

    // FR4, FR13: a check that CannotVerify escalates without burning an attempt, and the
    //     round is still recorded with its check results and usages (recorded, not counted)
    def "escalates CannotVerify recording the round without counting it"() {
        given: 'a stage whose single builtin check cannot be verified, run by a Completed executor'
        def stageDef = stage('build', 3, [builtin('files_exist')])
        builtinRunner.scripted << new Verdict.CannotVerify('binary not found', 'no such tool')
        executor.scripted << completed(ExecutorUsage.none())

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the outcome is Escalated(CannotVerify) naming the failing check with its reason and details'
        outcome instanceof TaskOutcome.Escalated
        def report = outcome.report() as EscalationReport.CannotVerify
        report.check() == CheckRef.of(0, stageDef.verify()[0])
        report.reason() == 'binary not found'
        report.details() == 'no such tool'

        and: 'the round was recorded but no attempt was burned'
        def finalState = outcome.finalState()
        finalState.attemptsUsed() == 0
        finalState.attempts().size() == 1

        and: 'the recorded round is round 0 carrying the CannotVerify check result'
        def record = finalState.attempts()[0]
        record.round() == 0
        record.checkResults().size() == 1
        record.checkResults()[0].checkRef() == CheckRef.of(0, stageDef.verify()[0])
        record.checkResults()[0].verdict() instanceof Verdict.CannotVerify

        and: 'the engine classified the round explicitly as CANNOT_VERIFY (FR13, D5)'
        // FR13: a CannotVerify round records the CANNOT_VERIFY result classification.
        record.result() == AttemptRecord.Result.CANNOT_VERIFY
    }

    // FR13: the recorded round carries the executor's ExecutorUsage and the verification's
    //     JudgeUsage — the metrics of the round land in attempts()[0]
    def "records the executor and judge usage of the round"() {
        given: 'a stage with a judge check that cannot verify, and a Completed executor reporting usage'
        def stageDef = stage('build', 3, [judge(1)])
        def executorUsage = new ExecutorUsage(Duration.ofSeconds(4), [], new TokenUsage(100, 40))
        executor.scripted << completed(executorUsage)
        def voteTokens = new TokenUsage(7, 3)
        judgeVoter.scripted << new JudgeVoter.Vote(new Verdict.CannotVerify('model unparseable', 'gibberish'), voteTokens)

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the round records the executor usage verbatim'
        def record = outcome.finalState().attempts()[0]
        record.executorUsage().is(executorUsage)

        and: 'and the judge usage gathered during verification'
        record.judgeUsage().perVote() == [voteTokens]
    }

    // FR13: the executor is invoked exactly once with attempt 0 and the run's stage and context
    def "invokes the executor once with attempt zero and the run's stage and context"() {
        given: 'a stage whose check cannot verify so the run terminates after one round'
        def stageDef = stage('build', 3, [builtin('files_exist')])
        builtinRunner.scripted << new Verdict.CannotVerify('binary not found', '')
        executor.scripted << completed(ExecutorUsage.none())

        when: 'the run is driven'
        new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the executor was called exactly once'
        executor.requests.size() == 1

        and: 'the single request carries attempt 0 and the run\'s stage and context'
        def request = executor.requests[0]
        request.attempt() == 0
        request.stage().is(stageDef)
        request.context().is(CONTEXT)
    }
}
