package com.github.oinsio.gnomish.status

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.DescribedExecutors
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.PendingState
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.fake.CheckRouting
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCheckRounds
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import spock.lang.Specification

/**
 * NFR-O1 of migrate-verify-to-executor-contract: each poll's line is written under the task's MDC
 * — the {@code stage} and {@code attempt} {@link MdcEventListener} sets from the poll's own {@link
 * com.github.oinsio.gnomish.domain.engine.EngineEvent.CheckPolled} before {@link
 * LoggingEventListener} renders it ({@code taskId} is the runner's, set before the engine starts).
 * A real engine run on virtual time, the two listeners composed in the order the runners compose
 * them.
 */
class PendingPollMdcSpec extends Specification {

    static final AttemptKey KEY = new AttemptKey('TASK-7', 'build', 0)
    static final ToolTrace TRACE = new ToolTrace(KEY, [])

    // NFR-O1: each poll logs under the task's MDC
    def "each poll's line carries the stage and attempt of the check it polls"() {
        given:
        def checks = new ScriptedExecutor([
            result(new ExecutionResult.Pending(new PendingKey('k-1'), null, PendingState.RUNNING, null, null), null),
            result(new ExecutionResult.Completed(), new ExecutionResult.VerdictReply(new VerdictAnswer.Sarif([], null)))
        ])
        def stage = new StageDefinition('build', 'purpose', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
        'instructions.md', [
            VerifyChecks.external('ci', 'scm-status', Duration.ofSeconds(30), Duration.ofMinutes(10), VerifyCheck.TimeoutClass.QUALITY)
        ], new AutonomyLimits(3), AdvancementMode.AUTO)
        def stages = new ScriptedExecutor([
            result(new ExecutionResult.Completed(), null)
        ])
        def ports = new EnginePorts(CheckRouting.of(stages, new ScriptedCheckRounds(), new ScriptedCheckRounds(), checks),
                new ScriptedJudgeVoter(),
                new CompositeEngineEventListener([
                    new MdcEventListener(),
                    new LoggingEventListener()
                ]),
                new InMemoryAttemptPersistence(), VirtualTimeEquipment.on(new VirtualClock()),
                AttemptDelivery.assumedDelivered(), DescribedExecutors.none(), RoundIdentity.inPlace())
        def logs = LogCaptureSupport.attach(LoggingEventListener, Level.DEBUG)

        when:
        new Engine().run(new PipelineDefinition('1', new AutonomyLimits(5), [stage]),
        new TaskContext('TASK-7', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        TaskState.atStageStart('build'), new FakeWorkspace(), ports)

        then:
        def polls = logs.list.findAll {
            it.formattedMessage.startsWith('pending poll:')
        }
        polls.size() == 1
        polls[0].MDCPropertyMap.subMap(['stage', 'attempt']) == [stage: 'build', attempt: '0']

        cleanup:
        logs.detach()
        MdcEventListener.clearAttemptScope()
    }

    private static ExecutionResult result(ExecutionResult.Status status, ExecutionResult.Answer answer) {
        new ExecutionResult(status, answer, ExecutorUsage.none(), TRACE, [])
    }
}
