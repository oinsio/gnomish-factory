package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.environment.ChildEnvAllowlist
import com.github.oinsio.gnomish.adapter.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR4 of add-sandbox-core: the host-mode round environment source opens each round as a
 * fresh host environment over the DirectoryWorkspace root with the temp-dir decision-file
 * transport — the round hands the executor the transport's decision-file path and the matching
 * {@code GNOMISH_DECISION_FILE} env fragment.
 */
class HostRoundEnvironmentSourceSpec extends Specification {

    @TempDir
    Path workspaceDir

    @TempDir
    Path decisionRoot

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

    // FR2, FR4: the round's decision-file path is the transport's, never null — the executor
    // wires it into the CLI flags' pinpoint Write allowance and the process env fragment.
    def "openRound exposes the transport's decision-file path and the matching env fragment"() {
        given:
        def source = new HostRoundEnvironmentSource(
                new DecisionFileTransport(decisionRoot), new VirtualClock(), ChildEnvAllowlist.none())

        when:
        def round = source.openRound(request())

        then:
        round.environment() instanceof HostTaskExecutionEnvironment
        round.decisionFilePath() != null
        round.decisionFilePath().startsWith(decisionRoot)
        round.decisionEnvFragment() == [GNOMISH_DECISION_FILE: round.decisionFilePath().toString()]

        cleanup:
        round.discard()
    }
}
