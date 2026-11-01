package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR7 of define-executor-contract (design D10, task 7.4): an {@code agent-cli} round killed on its
 * {@code roundTimeout} is reported by the adapter as the contract's {@code failed/infrastructure}
 * status, and the engine's classifier — not the adapter — makes it a {@code CannotExecute}
 * escalation. Driven end to end: the real {@link CliStageExecutor} over the fake agent binary,
 * under the real {@link Engine}.
 */
class AgentRoundContractStatusSpec extends Specification {

    @TempDir
    Path workspaceDir

    def clock = new VirtualClock()

    def listener = new RecordingEventListener()

    def persistence = new InMemoryAttemptPersistence()

    def setup() {
        Files.writeString(workspaceDir.resolve('instructions.md'), 'Do the thing.')
    }

    private TaskOutcome runHungRound() {
        def executor = new CliStageExecutor(
                FakeAgentSupport.propertiesFor('hangs-forever'), clock, PipelineLaw.ofContent(['instructions.md': 'Do the thing.']))
        def stage = FakeAgentSupport.requestFor(workspaceDir, [roundTimeout: 1]).stage()
        def ports = new EnginePorts(executor, new ScriptedBuiltinCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), listener, persistence,
                VirtualTimeEquipment.on(clock), RoundIdentity.inPlace())
        new Engine().run(new PipelineDefinition('1', new AutonomyLimits(3), [stage]),
        FakeAgentSupport.defaultTaskContext(), TaskState.atStageStart('build'), new DirectoryWorkspace(workspaceDir),
        ports) as TaskOutcome
    }

    // Scenario: executor/agent-executor / Adapter constructs no failure class
    // FR7: the adapter reports failed/infrastructure; the engine classifies it as CannotExecute
    def "FR7: a round killed on its timeout is reported as failed/infrastructure and classified by the engine"() {
        given:
        def round = LogCaptureSupport.attach('com.github.oinsio.gnomish.domain.engine.RoundFailures', Level.DEBUG)

        when:
        def outcome = runHungRound()

        then: 'the adapter reported the contract status — the round event carries it'
        def finished = listener.events.findAll {
            it instanceof EngineEvent.ExecutionFinished
        }
        finished.size() == 1
        def status = finished[0].status() as ExecutionResult.Failed
        status.failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
        status.reason().contains(RoundTimeoutException.name)

        and: 'the engine classified it: CannotExecute, no attempt burned, nothing recorded'
        outcome instanceof TaskOutcome.Escalated
        def report = outcome.report() as EscalationReport.CannotExecute
        report.cause().contains('exceeded roundTimeout')
        outcome.finalState().attemptsUsed() == 0
        outcome.finalState().attempts().isEmpty()
        persistence.entries.isEmpty()

        and: 'nothing was thrown at the engine: one reported-failure WARN, no executor-threw ERROR'
        def operator = round.list.findAll {
            it.level.isGreaterOrEqual(Level.WARN)
        }
        operator*.level == [Level.WARN]
        operator[0].formattedMessage.startsWith(OperatorEvent.EXECUTOR_REPORTED_FAILURE.head())
        !round.list.any {
            it.formattedMessage.startsWith(OperatorEvent.EXECUTOR_THREW.head())
        }

        cleanup:
        round.detach()
    }
}
