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
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * A pass keeps its below-threshold findings in the attempt record, and they feed no later attempt:
 * the retry feedback is the non-pass check results of prior attempts only (FR7 of
 * migrate-verify-to-executor-contract, design D5 as amended; FR4 of add-stage-engine).
 */
class PassFindingsFeedbackSpec extends Specification {

    def executor = new ScriptedExecutor()
    def builtinRunner = new ScriptedBuiltinCheckRunner()
    def persistence = new InMemoryAttemptPersistence()
    def clock = new VirtualClock()

    EnginePorts ports() {
        new EnginePorts(executor, builtinRunner, new ScriptedCommandCheckRunner(), new ScriptedExternalCheckClient(),
                new ScriptedJudgeVoter(), new RecordingEventListener(), persistence,
                VirtualTimeEquipment.on(clock, new VirtualSleeper(clock)), RoundIdentity.inPlace())
    }

    static PipelineDefinition pipeline() {
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [
            VerifyChecks.builtin('lint', [:]),
            VerifyChecks.builtin('tests', [:])
        ],
        new AutonomyLimits(5), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(5), [stage])
    }

    static ExecutionResult completed() {
        ExecutionResult.completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []), [])
    }

    // Scenario: verification/verdict-executors / A pass keeps its findings across the state file
    def "FR7: a pass with findings is recorded with them and feeds nothing into the next attempt"() {
        given: 'round 0: lint passes with two warnings, tests fail; round 1: lint cannot verify'
        def warnings = new VerdictAnswer.Sarif([
            new VerdictAnswer.Result(ResultLevel.WARNING, new Finding('w1', 'src/A.java:1', null)),
            new VerdictAnswer.Result(ResultLevel.WARNING, new Finding('w2', 'src/B.java:2', null)),
        ])
        def failed = new Verdict.Fail([
            new Finding('red test', null, null)
        ])
        builtinRunner.scripted << warnings
        builtinRunner.scripted << failed
        builtinRunner.scripted << new Verdict.CannotVerify(UntrustedText.subprocess('gone'), UntrustedText.subprocess(''))
        executor.scripted << completed()
        executor.scripted << completed()

        when:
        def outcome = new Engine().run(pipeline(), new TaskContext('TASK-1', UntrustedText.tracker('t'),
                UntrustedText.tracker('b'), []), TaskState.atStageStart('build'), new FakeWorkspace(), ports())

        then: 'the first attempt records the pass with both warnings, in the order written'
        def first = outcome.finalState().attempts()[0].checkResults()
        first[0].verdict() == new Verdict.Pass(null, [
            new Finding('w1', 'src/A.java:1', null),
            new Finding('w2', 'src/B.java:2', null)
        ])

        and: 'the retry feedback is the failed check alone'
        executor.requests.size() == 2
        def feedback = executor.requests[1].feedback()
        feedback*.verdict() == [failed]
    }
}
