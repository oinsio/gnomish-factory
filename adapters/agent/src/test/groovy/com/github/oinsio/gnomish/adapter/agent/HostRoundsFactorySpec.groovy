package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2 of wire-host-mid-round-push (design D2): the public {@code CliStageExecutor.hostRounds}
 * factory returns the exact host-mode round source the host convenience constructor builds —
 * so bootstrap can decorate it and hand it back through the rounds-accepting constructor
 * without a second listener-wiring mechanism appearing.
 */
class HostRoundsFactorySpec extends Specification {

    @TempDir
    Path workspaceDir

    private StageExecutor.Request request() {
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new StageExecutor.Request(
                new TaskContext('TASK-1', 'title', 'body', []),
                stage, new DirectoryWorkspace(workspaceDir), 0, [])
    }

    // FR2: the factory's rounds behave identically to the host constructor's — a fresh host
    // environment over the workspace root, the temp-dir decision transport's path/env pair,
    // and the seam's default no-op roundListener (the decorator overrides it, nothing else).
    def "hostRounds opens rounds identical to the host constructor's"() {
        given:
        def source = CliStageExecutor.hostRounds(new VirtualClock(), ChildEnvAllowlist.none())

        when:
        def round = source.openRound(request())

        then:
        round.environment() instanceof HostTaskExecutionEnvironment
        round.decisionFilePath() != null
        round.decisionEnvFragment() == [GNOMISH_DECISION_FILE: round.decisionFilePath().toString()]

        when: 'the default listener is exercised'
        round.roundListener().onProgress(new AgentProgressEvent.ToolStarted('Bash'))

        then: 'it is the seam default: a no-op that neither throws nor observes anything'
        noExceptionThrown()

        cleanup:
        round.discard()
    }
}
