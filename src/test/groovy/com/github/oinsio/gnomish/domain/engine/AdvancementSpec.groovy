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
import java.time.Duration
import spock.lang.Specification

/**
 * Engine stage-to-stage advancement, task 5.8 — how the engine drives a passing round
 * onward. After a stage's verification passes, {@code auto} advancement proceeds to the
 * next stage (or {@code Completed} with the position at the explicit pipeline end after
 * the last stage), resetting the attempt history for the fresh stage (FR14); {@code manual}
 * advancement returns {@code Paused} with the position already advanced past the paused
 * stage, naming the stage that just passed (FR8). A manual pause on the final stage parks
 * the position at the pipeline end, from which a subsequent run returns {@code Completed}
 * immediately without invoking the executor or persistence port.
 *
 * <p>Implements FR8, FR14 of add-stage-engine.
 */
class AdvancementSpec extends Specification {

    static final def WORKSPACE = new FakeWorkspace()
    static final def CONTEXT = new TaskContext('TASK-1', 'title', 'body', [])

    def executor = new ScriptedExecutor()
    def builtinRunner = new ScriptedBuiltinCheckRunner()
    def persistence = new InMemoryAttemptPersistence()
    def listener = new RecordingEventListener()
    def clock = new VirtualClock()
    def sleeper = new VirtualSleeper(clock)

    EnginePorts ports() {
        new EnginePorts(executor, builtinRunner, new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), listener, persistence, clock, sleeper)
    }

    static VerifyCheck.Builtin builtin(String name) {
        new VerifyCheck.Builtin(name, [:])
    }

    static StageDefinition stage(String name, AdvancementMode advancement, List<VerifyCheck> verify) {
        new StageDefinition(name, 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', verify, new AutonomyLimits(3), advancement)
    }

    static PipelineDefinition pipeline(StageDefinition... stages) {
        new PipelineDefinition('1', new AutonomyLimits(3), stages.toList())
    }

    static ExecutionResult.Completed completed() {
        new ExecutionResult.Completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []))
    }

    static ExecutionResult.Completed completed(ExecutorUsage usage) {
        new ExecutionResult.Completed(usage, new ToolTrace(new AttemptKey('TASK-1', 'build', 0), []))
    }

    static ExecutorUsage usage(long wallSecs, long tokensIn, long tokensOut) {
        new ExecutorUsage(Duration.ofSeconds(wallSecs),
                [
                    new ToolUsage('read', 1, Duration.ofMillis(10))
                ], ['model-a': new TokenUsage(tokensIn, tokensOut, 0, 0)])
    }

    // FR8: a one-stage auto pipeline that passes verification completes with the position
    //      parked at the explicit pipeline end and an empty attempt history
    def "an auto stage that passes completes at the pipeline end with empty history"() {
        given: 'a single auto stage with a vacuously-passing (empty) verify chain'
        def stageDef = stage('build', AdvancementMode.AUTO, [])
        executor.scripted << completed()

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stageDef), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the outcome is Completed with the position at the pipeline end'
        outcome instanceof TaskOutcome.Completed
        outcome.finalState().position() instanceof Position.PipelineEnd

        and: 'the advanced state history is empty (FR14)'
        outcome.finalState().attempts().isEmpty()
        outcome.finalState().attemptsUsed() == 0
    }

    // FR8/FR14: a two-stage auto pipeline runs each stage once and resets the attempt
    //      history between stages — the second stage's executor request starts at attempt 0
    def "an auto pipeline advances stage to stage resetting history between stages"() {
        given: 'two auto stages, each with its own passing builtin check so both are observed'
        def stage1 = stage('build', AdvancementMode.AUTO, [builtin('build_check')])
        def stage2 = stage('test', AdvancementMode.AUTO, [builtin('test_check')])
        builtinRunner.scripted << new Verdict.Pass()
        builtinRunner.scripted << new Verdict.Pass()
        executor.scripted << completed(usage(5, 100, 20))
        executor.scripted << completed(usage(3, 40, 10))

        when: 'the run is driven through both stages'
        def outcome = new Engine().run(pipeline(stage1, stage2), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the outcome is Completed at the pipeline end'
        outcome instanceof TaskOutcome.Completed
        outcome.finalState().position() instanceof Position.PipelineEnd

        and: 'the executor was invoked once per stage'
        executor.requests.size() == 2

        and: 'both stages ran — each check runner saw its own stage check'
        builtinRunner.calls.collect { it.name() } == ['build_check', 'test_check']

        and: 'the second stage started fresh — its executor request is attempt 0 (history reset, FR14)'
        executor.requests[1].stage().name() == 'test'
        executor.requests[1].attempt() == 0

        and: 'each passing round was recorded (before its advancement reset) with the PASSED result (FR13, D5)'
        // FR13: a round whose verification passes records the PASSED result classification. The
        // persisted state captures the round before advancement clears the history for the next stage.
        persistence.entries[0].state.attempts()[0].result() == AttemptRecord.Result.PASSED
        persistence.entries[1].state.attempts()[0].result() == AttemptRecord.Result.PASSED

        and: 'the task totals survive advancement, summing every stage\'s executor usage (FR13, NFR-C1, D5)'
        // Cumulative totals survive advancement: stage 1's persisted totals hold only its own
        // usage, then stage 2 carries it forward and folds its own usage into the final total.
        persistence.entries[0].state.totals() == usage(5, 100, 20)
        outcome.finalState().totals().wallTime() == Duration.ofSeconds(8)
        outcome.finalState().totals().tokensByModel() == ['model-a': new TokenUsage(140L, 30L, 0L, 0L)]
        outcome.finalState().totals().tools() == [
            new ToolUsage('read', 2, Duration.ofMillis(20))
        ]
    }

    // FR8: a manual first stage that passes returns Paused, naming the stage that passed,
    //      with the position already advanced past it to the next stage; a subsequent run
    //      with that returned state starts at the next stage
    def "a manual stage that passes returns Paused advanced past it and a resume runs the next stage"() {
        given: 'a two-stage pipeline whose first stage is a manual checkpoint'
        def stage1 = stage('build', AdvancementMode.MANUAL, [])
        def stage2 = stage('test', AdvancementMode.AUTO, [])
        executor.scripted << completed()

        when: 'the run is driven'
        def outcome = new Engine().run(pipeline(stage1, stage2), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the outcome is Paused naming the stage that passed, positioned at the next stage'
        outcome instanceof TaskOutcome.Paused
        (outcome as TaskOutcome.Paused).passedStage() == 'build'
        outcome.finalState().position() == new Position.AtStage('test')

        when: 'a subsequent run resumes from the returned paused state'
        def secondExecutor = new ScriptedExecutor([completed()])
        def resumePorts = new EnginePorts(secondExecutor, new ScriptedBuiltinCheckRunner(),
                new ScriptedCommandCheckRunner(), new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(),
                new RecordingEventListener(), new InMemoryAttemptPersistence(), new VirtualClock(),
                new VirtualSleeper(new VirtualClock()))
        def resumed = new Engine().run(pipeline(stage1, stage2), CONTEXT, outcome.finalState(), WORKSPACE, resumePorts)

        then: 'the resume ran the next stage (test) and completed'
        resumed instanceof TaskOutcome.Completed
        secondExecutor.requests.size() == 1
        secondExecutor.requests[0].stage().name() == 'test'
    }

    // FR8: a manual pause on the LAST stage parks the position at the pipeline end; a
    //      subsequent run from that state returns Completed immediately, invoking neither
    //      the executor nor the persistence port
    def "a manual pause on the last stage parks at pipeline end and resumes to Completed with no ports touched"() {
        given: 'a two-stage pipeline whose final stage is a manual checkpoint'
        def stage1 = stage('build', AdvancementMode.AUTO, [])
        def stage2 = stage('test', AdvancementMode.MANUAL, [])
        executor.scripted << completed()
        executor.scripted << completed()

        when: 'the run is driven through to the manual pause on the last stage'
        def outcome = new Engine().run(pipeline(stage1, stage2), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports())

        then: 'the outcome is Paused with the position parked at the pipeline end'
        outcome instanceof TaskOutcome.Paused
        (outcome as TaskOutcome.Paused).passedStage() == 'test'
        outcome.finalState().position() instanceof Position.PipelineEnd

        when: 'a subsequent run resumes from the pipeline-end state'
        def resumeExecutor = new ScriptedExecutor()
        def resumePersistence = new InMemoryAttemptPersistence()
        def resumePorts = new EnginePorts(resumeExecutor, new ScriptedBuiltinCheckRunner(),
                new ScriptedCommandCheckRunner(), new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(),
                new RecordingEventListener(), resumePersistence, new VirtualClock(),
                new VirtualSleeper(new VirtualClock()))
        def resumed = new Engine().run(pipeline(stage1, stage2), CONTEXT, outcome.finalState(), WORKSPACE, resumePorts)

        then: 'the resume returns Completed immediately, touching neither the executor nor persistence'
        resumed instanceof TaskOutcome.Completed
        resumeExecutor.requests.isEmpty()
        resumePersistence.entries.isEmpty()
    }
}
