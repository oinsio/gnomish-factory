package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * NFR-O2, UX3 of define-executor-contract: every round's {@code ExecutionFinished} names the
 * executor that ran it — the stage's law-named executor ({@code executor.name}, task 6.1), with the
 * describe version the run obtained for it, or else the stage's executor type word with no version —
 * and the status it reached.
 */
class RoundExecutorEventSpec extends EventEmissionSpecBase {

    static final UntrustedText V231 = UntrustedText.subprocess('2.3.1')
    static final UntrustedText V1 = UntrustedText.subprocess('1')

    EnginePorts ports(DescribedExecutors described) {
        new EnginePorts(executor, builtinRunner, commandRunner, externalClient, judgeVoter, listener, persistence,
                VirtualTimeEquipment.on(clock, sleeper), AttemptDelivery.assumedDelivered(), described, RoundIdentity.inPlace())
    }

    static StageDefinition stageOf(ExecutorType type, String name = null) {
        new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(type, 'model', [:], Sandbox.none(), name),
        'instructions.md', [builtin('files_exist')], new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    EngineEvent.ExecutionFinished finishedOf(StageDefinition stage, DescribedExecutors described) {
        executor.scripted << completed()
        builtinRunner.scripted << new Verdict.Pass(null, [])
        new Engine().run(pipeline(stage), CONTEXT, TaskState.atStageStart('build'), WORKSPACE, ports(described))
        def finished = listener.events.findAll {
            it instanceof EngineEvent.ExecutionFinished
        }
        assert finished.size() == 1
        finished[0] as EngineEvent.ExecutionFinished
    }

    // NFR-O2, UX3 (task 6.1): the stage's executor.name is the round's executor, with the version
    //     the preflight described for that name
    def "a round of a stage naming a described executor names that executor and its version"() {
        given:
        def described = new DescribedExecutors(new ExecutorVersions(['lint-bot': V231, judge: V1]), ExecutorCredentialNames.none())

        when:
        def finished = finishedOf(stageOf(ExecutorType.PROGRAM, 'lint-bot'), described)

        then:
        finished.executor() == new RoundExecutor('lint-bot', V231)
        finished.status() == new ExecutionResult.Completed()
    }

    // NFR-O2, UX3: the law's name is kept even when the run described no version for it
    def "a round of a stage naming an undescribed executor names it with no version"() {
        when:
        def finished = finishedOf(stageOf(ExecutorType.PROGRAM, 'lint-bot'), described)

        then:
        finished.executor() == new RoundExecutor('lint-bot', null)

        where:
        described << [
            DescribedExecutors.none(),
            new DescribedExecutors(new ExecutorVersions([judge: V1]), ExecutorCredentialNames.none())
        ]
    }

    // NFR-O2, UX3: an executor with no name is named by the stage's executor type word, unversioned,
    //     however many executors the run described
    def "a round of a stage naming no executor names the executor type #word with no version"() {
        when:
        def finished = finishedOf(stageOf(type), new DescribedExecutors(new ExecutorVersions(['lint-bot': V231]), ExecutorCredentialNames.none()))

        then:
        finished.executor() == new RoundExecutor(word, null)

        where:
        type | word
        ExecutorType.AGENT_CLI | 'agent-cli'
        ExecutorType.API | 'api'
    }

    def "a round executor needs a name"() {
        when:
        new RoundExecutor(' ', null)

        then:
        thrown(IllegalArgumentException)
    }
}
