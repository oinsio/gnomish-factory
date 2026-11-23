package com.github.oinsio.gnomish.adapter.agent.runner

import com.github.oinsio.gnomish.adapter.agent.program.GuardedExecutor
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.agent.program.ProgramStageExecutor
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.app.port.check.FreshBoxSource
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.AttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The stage half of an agent registry entry, {@link AgentStageRounds} (FR14, FR15 of
 * extract-agent-round-runner; design D12, D13): a {@code transform} request built from the round
 * prompt and the stage block, run through the shared round wrapper over the round's environment,
 * behind the guard probe's one owner.
 */
@Timeout(60)
class AgentStageRoundsSpec extends AgentEntrySpecBase {

    // FR14, D12: the stage half's request is the round prompt as a transform, with the block's model and settings
    def "FR14: the stage half names the entry and asks a transform with the round prompt, the block's model and settings, and no continuation"() {
        given:
        def stage = new AgentStageRounds(runners(), law())

        when:
        def request = stage.request(stageRound(workspace, 1))

        then:
        stage.name() == 'claude'
        request.role() == Role.TRANSFORM
        request.workspace().path() == '.'
        request.policy() instanceof Policy.WorkspaceWrite
        request.roleInputs().instructions().contains('Build the widget.')
        request.roleInputs().instructions().contains('GNOMISH_ANSWER_FILE')
        request.roleInputs().instructions().contains('Rework, not restart')
        ExecutorContractJson.settingsValues(request.settings()) == [maxTurns: 4, model: 'claude-opus']
        request.bounds() == ProgramBounds.DEFAULTS.hints()
        request.feedback().isEmpty()
        request.continuation() == null
    }

    // FR14, FR15, D12, D13: the stage half answers through the dialect over the round's environment, probed around the request
    def "FR14, FR15: a stage round runs the runner over the round's environment through the shared wrapper, its guard probed before and after"() {
        given:
        stdout = 'hello m s\ndone built\n'
        def environment = host()
        def round = openRound(environment)
        def rounds = Mock(RoundEnvironmentSource)

        when:
        def result = new ProgramStageExecutor(new AgentStageRounds(runners(), law()), rounds).execute(stageRound(workspace))

        then:
        1 * rounds.openRound(_) >> round
        1 * round.closeRound(RoundClose.VERIFIABLE)
        1 * round.readDecision() >> Optional.empty()
        0 * round.discard()
        result.status() instanceof ExecutionResult.Completed
        agentLaunches*.command() == [['fake-agent', 'transform']]
        stdins.first().contains('Build the widget.')
        environment.probes == 2

        cleanup:
        environment.delegate.dispose()
    }

    // FR15, D13: a stage round whose guard is dead before the request launches no CLI and is infrastructure
    def "FR15: a stage round whose guard is dead is discarded as failed/infrastructure, and no CLI is launched"() {
        given:
        def environment = host(new IllegalStateException('egress guard is not running'))
        def round = openRound(environment)
        def rounds = Stub(RoundEnvironmentSource) {
            openRound(_) >> round
        }

        when:
        def result = new ProgramStageExecutor(new AgentStageRounds(runners(), law()), rounds).execute(stageRound(workspace))

        then: 'the wrapper names the agent executor, not a program'
        1 * round.discard()
        def failed = result.status() as ExecutionResult.Failed
        failed.failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
        failed.reason().contains('egress guard is not running')
        failed.reason().raw().startsWith("agent executor 'claude': ")
        agentLaunches.isEmpty()

        cleanup:
        environment.delegate.dispose()
    }

    // FR9, FR14, D3, task 5.1a: the open round's answer file is the request's one write beyond the
    //     working copy, the path the agent is told, and the path its decision request is read from
    def "FR9, FR14: a stage round asks writeExactly of the open round's answer file, hands the agent that path and returns the decision request written there"() {
        given:
        stdout = 'hello m s\ndone asked\n'
        asks = '{"kind":"decision-request","question":"Refactor or patch?","options":["refactor","patch"]}'
        def environment = host()
        def round = openRound(environment)
        def rounds = Stub(RoundEnvironmentSource) {
            openRound(_) >> round
        }

        when:
        def result = new ProgramStageExecutor(new AgentStageRounds(runners(), law()), rounds).execute(stageRound(workspace))

        then: 'the dialect rendered the policy of the round\'s answer file, and the agent was told that path'
        dialect.rendered*.policy() == [
            new Policy.WriteExactly(ANSWER.toString())
        ]
        agentLaunches.first().environment()['GNOMISH_ANSWER_FILE'] == ANSWER.toString()

        and: 'the round closed verifiable, and its result is the decision request the agent wrote'
        1 * round.closeRound(RoundClose.VERIFIABLE)
        result.status() instanceof ExecutionResult.Completed
        def asked = result.answer() as ExecutionResult.DecisionRequest
        asked.question().raw() == 'Refactor or patch?'
        asked.options()*.raw() == ['refactor', 'patch']

        cleanup:
        environment.delegate.dispose()
    }

    // FR6, FR14, task 5.1a: the round's own listener (the mid-round harvest or push) hears the round as it runs
    def "FR6, FR14: a stage round reports its progress to the open round's own listener"() {
        given:
        stdout = 'hello m s\nuse Edit\ndone built\n'
        List<AgentProgressEvent> heard = []
        def environment = host()
        def round = openRound(environment, ANSWER, { event ->
            heard << event
        } as AgentProgressListener)
        def rounds = Stub(RoundEnvironmentSource) {
            openRound(_) >> round
        }

        when:
        def result = new ProgramStageExecutor(new AgentStageRounds(runners(), law()), rounds).execute(stageRound(workspace))

        then:
        result.status() instanceof ExecutionResult.Completed
        heard*.class == [
            AgentProgressEvent.RoundStarted,
            AgentProgressEvent.ToolStarted,
            AgentProgressEvent.RoundFinished
        ]

        cleanup:
        environment.delegate.dispose()
    }

    // FR14: bound to a bare environment (the factory's binding without an open round), the stage half is the runner over it
    def "FR14: the stage half bound to an environment alone runs the runner over that environment"() {
        given:
        stdout = 'hello m s\ndone built\n'
        def environment = host()
        def stage = new AgentStageRounds(runners(), law())

        when:
        def result = stage.executor(environment).start(stage.request(stageRound(workspace)))

        then:
        result.status() == Status.COMPLETED
        agentLaunches*.command() == [['fake-agent', 'transform']]

        cleanup:
        environment.delegate.dispose()
    }

    /** What the agent writes to the answer file it was handed, or null for nothing. */
    String asks

    @Override
    protected Process launched(ProcessBuilder builder, StdinSource stdin) {
        if (asks != null && builder.command().first() != 'git') {
            def answer = workspace.resolve(builder.environment()['GNOMISH_ANSWER_FILE'])
            Files.createDirectories(answer.parent)
            Files.writeString(answer, asks)
        }
        super.launched(builder, stdin)
    }
}
