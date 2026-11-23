package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.CheckRouting
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCheckRounds
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.testfixtures.JudgeChecks
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * A {@code judge} check on the engine's one port (design D2 of extract-agent-round-runner): one
 * {@code verdict} request per check, the checks of a stage run in declaration order and stopping at
 * the first that does not pass, an answer that is no verdict a cannot-verify that burns no attempt,
 * and each judge request a participant of the round with its ordinal (FR4, M3 of
 * extract-agent-round-runner).
 */
class JudgeCheckRoundSpec extends Specification {

    static final def WORKSPACE = new FakeWorkspace()
    static final def CONTEXT = new TaskContext('TASK-1', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])

    def executor = new ScriptedExecutor()
    def judges = new ScriptedCheckRounds()
    def persistence = new InMemoryAttemptPersistence()

    EnginePorts ports() {
        new EnginePorts(CheckRouting.of(executor, new ScriptedCheckRounds(), new ScriptedCheckRounds(), new ScriptedCheckRounds(), judges),
                new RecordingEventListener(), persistence, VirtualTimeEquipment.on(new VirtualClock()), RoundIdentity.inPlace())
    }

    static PipelineDefinition twoJudges() {
        def stage = new StageDefinition('review', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [
            JudgeChecks.agent('first.md', 'model-a'),
            JudgeChecks.agent('second.md', 'model-b')
        ],
        new AutonomyLimits(1), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
    }

    static ExecutionResult completed() {
        ExecutionResult.completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey('TASK-1', 'review', 0), []), [])
    }

    static ExecutionResult verdict(VerdictAnswer answer, Map<String, TokenUsage> tokens) {
        new ExecutionResult(new ExecutionResult.Completed(), new ExecutionResult.VerdictReply(answer),
                new ExecutorUsage(Duration.ZERO, [], Unpriced.of(tokens)),
                new ToolTrace(new AttemptKey('TASK-1', 'review', 0), []), [])
    }

    static VerdictAnswer failing(String message) {
        new VerdictAnswer.Sarif([
            new VerdictAnswer.Result(ResultLevel.ERROR, new Finding(message, null, null))
        ], null)
    }

    static final VerdictAnswer PASSING = new VerdictAnswer.Sarif([], null)

    // FR4: a failing first judge stops the chain; the second is never asked
    // Scenario: pipeline/stage-engine / A second judge runs only after the first passes
    def "a second judge check is requested only after the first passes, and its verdict decides the stage"() {
        given:
        executor.scripted << completed()
        judges.scripted.addAll(first)

        when: 'the one round the stage allows is driven'
        new Engine().run(twoJudges(), CONTEXT, TaskState.atStageStart('review'), WORKSPACE, ports())

        then: 'the judges of the first round were asked in declaration order, the second only after the first passed'
        judges.calls*.checkId() == asked

        and: 'the last asked judge decided the round'
        persistence.entries[0].state.attempts()[0].result() == result

        where:
        first || asked | result
        [
            verdict(failing('weak tests'), [:])
        ] || ['first.md'] | AttemptRecord.Result.QUALITY_FAILURE
        [
            verdict(PASSING, [:]),
            verdict(failing('wrong name'), [:])
        ] || ['first.md', 'second.md'] | AttemptRecord.Result.QUALITY_FAILURE
        [
            verdict(PASSING, [:]),
            verdict(PASSING, [:])
        ] || ['first.md', 'second.md'] | AttemptRecord.Result.PASSED
    }

    // FR4: an answer that is no verdict cannot verify, and burns no attempt
    // Scenario: pipeline/stage-engine / A malformed judge answer is cannot-verify
    // Scenario: verification/verification-hardening / Malformed judge verdict burns no attempt
    def "a malformed judge answer is cannot-verify and burns no attempt"() {
        given:
        executor.scripted << completed()
        judges.scripted << new VerdictAnswer.Malformed(UntrustedText.agent('no JSON object'))

        when:
        def outcome = new Engine().run(twoJudges(), CONTEXT, TaskState.atStageStart('review'), WORKSPACE, ports())

        then:
        outcome instanceof TaskOutcome.Escalated
        (outcome as TaskOutcome.Escalated).report() instanceof EscalationReport.CannotVerify
        outcome.finalState().attemptsUsed() == 0

        and: 'the second judge was never asked'
        judges.calls*.checkId() == ['first.md']
    }

    static PipelineDefinition agentAndProgramJudges() {
        def stage = new StageDefinition('review', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [
            JudgeChecks.agent('first.md', 'model-a'),
            JudgeChecks.program('second.md', 'acme-judge')
        ],
        new AutonomyLimits(1), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
    }

    // FR4 of extract-agent-round-runner, FR15 of define-executor-contract: each judge check's
    //     request is the round's verdict participant, named by the registry entry its block
    //     resolved to (agent-cli -> claude, a program block -> its declaration), its vote the
    //     0-based ordinal among the round's judge checks, its own byModel (task 4.3)
    // Scenario: observability/status-report / Judge votes are participants
    def "a round that ran two judge checks records two verdict participants named by their entries, votes 0 and 1"() {
        given:
        executor.scripted << completed()
        def tokensA = ['model-a': new TokenUsage(100, 20, 0, 0)]
        def tokensB = ['model-b': new TokenUsage(150, 30, 0, 0)]
        judges.scripted.addAll([
            verdict(PASSING, tokensA),
            verdict(PASSING, tokensB)
        ])
        def routed = new EnginePorts(
                CheckRouting.of(executor, new ScriptedCheckRounds(), new ScriptedCheckRounds(), judges, judges),
                new RecordingEventListener(), persistence, VirtualTimeEquipment.on(new VirtualClock()), RoundIdentity.inPlace())

        when:
        new Engine().run(agentAndProgramJudges(), CONTEXT, TaskState.atStageStart('review'), WORKSPACE, routed)

        then: 'each judge request went to the entry its block resolved to'
        judges.calls*.executorName() == [
            BuiltInExecutors.CLAUDE,
            'acme-judge'
        ]

        and: 'the round recorded by the persisted state names each participant by that entry'
        def usage = persistence.entries[0].state.attempts()[0].usage()
        def verdicts = usage.findAll { it.role() == ExecutorRole.VERDICT }
        verdicts*.executor() == [
            BuiltInExecutors.CLAUDE,
            'acme-judge'
        ]
        verdicts*.vote() == [0, 1]
        verdicts*.byModel() == [
            Unpriced.of(tokensA),
            Unpriced.of(tokensB)
        ]
    }

    // M3: the engine's ports hold one executor-facing port
    // Scenario: pipeline/stage-engine / Only one executor-facing port remains
    def "the engine ports record holds one StageExecutor and no judge or check port"() {
        when:
        def types = EnginePorts.recordComponents*.type

        then:
        types.count { StageExecutor.isAssignableFrom(it) } == 1
        types.findAll {
            it.simpleName.contains('Judge') || it.simpleName.contains('Check') || it.simpleName.contains('Voter')
        } == []
    }
}
