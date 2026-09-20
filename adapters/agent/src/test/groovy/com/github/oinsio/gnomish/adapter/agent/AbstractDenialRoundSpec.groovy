package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
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

    // Delegates to FakeAgentSupport#requestFor, the single owner of this fixture shape.
    protected StageExecutor.Request requestFor(Map<String, Object> settings = [:]) {
        FakeAgentSupport.requestFor(workspaceDir, settings)
    }
}
