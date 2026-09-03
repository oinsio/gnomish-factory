package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Shared fixture for {@link ExecutorRoundDenialSpec} and {@link FailedRoundDenialSpec}: both
 * drive the real host round through the fake agent binary with only the environment
 * substituted, so this base wires the production path (host environment source, stage
 * request, executor) once instead of twice.
 */
abstract class AbstractDenialRoundSpec extends Specification {

    protected static final PipelineLaw LAW = PipelineLaw.ofContent(['instructions.md': 'Do the thing.'])

    @TempDir
    Path workspaceDir

    @TempDir
    Path decisionRoot

    def clock = new VirtualClock()

    def setup() {
        Files.writeString(workspaceDir.resolve('instructions.md'), 'Do the thing.')
    }

    protected HostRoundEnvironmentSource hostSource() {
        new HostRoundEnvironmentSource(new DecisionFileTransport(decisionRoot), clock, ChildEnvAllowlist.none())
    }

    protected StageExecutor executorFor(String scenario, RoundEnvironmentSource source) {
        new CliStageExecutor(
                FakeAgentSupport.propertiesFor(scenario), clock,
                { AgentProgressEvent e -> } as AgentProgressListener, LAW, source)
    }

    protected StageExecutor.Request requestFor(Map<String, Object> settings = [:]) {
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', settings),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new StageExecutor.Request(
                new TaskContext('TASK-1', 'title', 'body', []),
                stage, new DirectoryWorkspace(workspaceDir), 0, [])
    }
}
