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
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import spock.lang.Specification

/**
 * What one engine run promises its caller beyond the outcome itself: the time it reads, the errors
 * it does not swallow, the usage totals it carries across a resume, and the tool trace it keeps out
 * of the state (FR13, FR14, NFR-C1 of add-stage-engine; FR15 of add-manual-run; FR17 of
 * supervise-daemon-loops-and-embed-dashboard).
 */
class EngineRunContractSpec extends Specification {

    static final def CONTEXT = new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
    static final Instant START = Instant.parse('2026-10-12T08:00:00Z')

    def executor = new ScriptedExecutor()
    def builtinRunner = new ScriptedBuiltinCheckRunner()
    def externalClient = new ScriptedExternalCheckClient()
    def persistence = new InMemoryAttemptPersistence()
    def clock = new VirtualClock(START)
    def sleeper = new VirtualSleeper(clock)

    private TaskOutcome run(List<VerifyCheck> verify, int attemptLimit = 3, TaskState state = TaskState.atStageStart('build')) {
        def stage = new StageDefinition('build', 'purpose', [], [], new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', verify, new AutonomyLimits(attemptLimit), AdvancementMode.AUTO)
        def ports = new EnginePorts(executor, builtinRunner, new ScriptedCommandCheckRunner(), externalClient,
                new ScriptedJudgeVoter(), new RecordingEventListener(), persistence, VirtualTimeEquipment.on(clock, sleeper))
        new Engine().run(new PipelineDefinition('1', new AutonomyLimits(attemptLimit), [stage]), CONTEXT, state, new FakeWorkspace(), ports)
    }

    private static ExecutionResult.Completed completed(int attempt, ExecutorUsage usage, List<ToolCall> calls = []) {
        new ExecutionResult.Completed(usage, new ToolTrace(new AttemptKey('TASK-1', 'build', attempt), calls), [])
    }

    private static ExecutorUsage usage(long wallSecs, long tokensIn) {
        new ExecutorUsage(Duration.ofSeconds(wallSecs), [
            new ToolUsage('read', 1, Duration.ofMillis(10))
        ],
        ['model-a': new TokenUsage(tokensIn, 0, 0, 0)])
    }

    // FR17 of supervise-daemon-loops-and-embed-dashboard: one InstantSource, read for every instant
    // Scenario: pipeline/stage-engine / The engine's time source is the shared one
    def "every timestamp and deadline the engine computes reads the instant source it was assembled with"() {
        given: 'an external check that never settles, on a 1s interval and a 3s timeout'
        externalClient.scripted.addAll((1..10).collect {
            new PollStatus.Running()
        })
        executor.scripted << completed(0, ExecutorUsage.none())

        when:
        def outcome = run([
            new VerifyCheck.External('ci', 'ci-platform', Duration.ofSeconds(1), Duration.ofSeconds(3), VerifyCheck.TimeoutClass.QUALITY)
        ], 1)

        then: 'the round began at the source\'s reading, and the deadline ran out on the same source'
        def round = outcome.finalState().attempts()[0]
        round.startedAt() == START
        round.checkResults()[0].duration() == Duration.ofSeconds(3)
        clock.instant() == START + Duration.ofSeconds(3)

        and: 'the type between the engine and the source is the JDK\'s own'
        EnginePorts.recordComponents.find {
            it.name == 'time'
        }.type == TimeEquipment
        TimeEquipment.recordComponents.find {
            it.name == 'clock'
        }.type == InstantSource
    }

    // FR10 of add-stage-engine: only port failures become outcomes; the engine's own faults escape
    // Scenario: pipeline/stage-engine / An engine-internal error is thrown, not returned
    def "an invariant the engine's own logic breaks propagates out of the run instead of becoming an outcome"() {
        given: 'a check during which the instant source steps back, so the engine computes a negative duration'
        executor.scripted << completed(0, ExecutorUsage.none())
        builtinRunner.scripted << new Verdict.Pass()
        builtinRunner.onRun = { check, workspace ->
            clock.advance(Duration.ofSeconds(-5))
        }

        when:
        run([
            new VerifyCheck.Builtin('lint', [:])
        ])

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('duration')

        and: 'no round was persisted as if the run had reached a result'
        persistence.entries.isEmpty()
    }

    // FR13, NFR-C1 of add-stage-engine: the totals are the persisted ones plus every round since
    // Scenario: pipeline/stage-engine / Cumulative totals survive resume
    def "a resumed run starts its totals from the persisted ones and adds every round it records"() {
        given: 'a persisted state that already carries totals from earlier rounds'
        def persisted = new TaskState(new Position.AtStage('build'), 0, [], usage(7, 1000))
        builtinRunner.scripted << new Verdict.Fail([
            new Finding('red', null, null)
        ]) << new Verdict.Pass()
        executor.scripted << completed(0, usage(2, 50)) << completed(1, usage(3, 20))

        when:
        def outcome = run([
            new VerifyCheck.Builtin('lint', [:])
        ], 3, persisted)

        then: 'each recorded round folded into the running totals'
        persistence.entries*.state*.totals()*.tokensByModel() == [
            ['model-a': new TokenUsage(1050, 0, 0, 0)],
            ['model-a': new TokenUsage(1070, 0, 0, 0)]
        ]
        outcome.finalState().totals().wallTime() == Duration.ofSeconds(12)
    }

    // FR14 of add-stage-engine: aggregates in the state, the chronological trace beside it
    // Scenario: pipeline/stage-engine / The raw tool trace stays out of the state
    def "the state keeps the round's aggregates while its tool trace travels beside it under the round's key"() {
        given:
        def calls = [
            new ToolCall(0, 'read', START, Duration.ofMillis(4)),
            new ToolCall(1, 'read', START, Duration.ofMillis(6))
        ]
        executor.scripted << completed(0, usage(2, 50), calls)

        when:
        run([], 3)

        then: 'the persisted round holds the per-tool aggregate, never the calls'
        def entry = persistence.entries[0]
        entry.state.attempts()[0].executorUsage() == usage(2, 50)
        !AttemptRecord.recordComponents*.type.contains(ToolTrace)
        !TaskState.recordComponents*.type.contains(ToolTrace)

        and: 'the trace reaches persistence beside the state, keyed (taskId, stage, attempt)'
        entry.trace.calls() == calls
        entry.trace.key() == new AttemptKey('TASK-1', 'build', entry.state.attempts()[0].round())
    }
}
