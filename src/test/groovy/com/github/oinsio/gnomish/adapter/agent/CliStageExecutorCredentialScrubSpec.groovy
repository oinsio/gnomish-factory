package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.fake.FakeAgentBinary
import com.github.oinsio.gnomish.adapter.environment.ChildEnvAllowlist
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * D17, NFR-S1 of add-tracker-port; FR9, D6 of add-sandbox-core: {@link CliStageExecutor}'s
 * allowlist constructor threads the {@link ChildEnvAllowlist} into the {@code
 * HostTaskExecutionEnvironment} it runs each round through, which composes the child environment
 * as base ∪ passthrough ∪ factory-set with declared credential names excluded — a focused
 * unit-level proof, one rung below {@code TakeCommandCredentialScrubSpec}'s full {@code
 * take}-flavored end-to-end wiring proof. HOME doubles as the observable credential: it sits in
 * the host base set, so its absence can only come from the credential exclusion.
 */
class CliStageExecutorCredentialScrubSpec extends Specification {

    private static final String CREDENTIAL_VAR = 'HOME'

    private static final PipelineLaw LAW = PipelineLaw.ofContent(['instructions.md': 'Do the thing.'])

    @TempDir
    Path workspaceDir

    def clock = new VirtualClock()

    def setup() {
        Files.writeString(workspaceDir.resolve('instructions.md'), 'Do the thing.')
    }

    private Path reportPath
    private FactoryProperties wrapperReporting() {
        reportPath = workspaceDir.resolve('credential-report.txt')
        def wrapper = File.createTempFile('cli-stage-executor-cred-scrub', '.sh')
        wrapper.text = """#!/bin/sh
export GNOMISH_FAKE_SCENARIO='plain-round'
if [ -n "\${${CREDENTIAL_VAR}:-}" ]; then
    echo 'present' >> '${reportPath}'
else
    echo 'absent' >> '${reportPath}'
fi
exec sh '${FakeAgentBinary.commandPrefix()[1]}' "\$@"
"""
        wrapper.setExecutable(true)
        wrapper.deleteOnExit()
        new FactoryProperties('factory-01', wrapper.absolutePath, [], null, null)
    }

    private static StageExecutor.Request requestFor(Path workspaceDir) {
        def stage = new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
        new StageExecutor.Request(
                new TaskContext('TASK-1', 'title', 'body', []),
                stage, new DirectoryWorkspace(workspaceDir), 0, [])
    }

    def "a declared credential in the allowlist never reaches the spawned process"() {
        given:
        def executor = new CliStageExecutor(
                wrapperReporting(),
                clock,
                { event -> } as AgentProgressListener,
                ChildEnvAllowlist.of([], [CREDENTIAL_VAR]),
                LAW)

        when:
        executor.execute(requestFor(workspaceDir))

        then:
        reportPath.toFile().text.trim() == 'absent'
    }

    def "with nothing declared, the same base variable reaches the spawned process"() {
        given:
        def executor = new CliStageExecutor(
                wrapperReporting(), clock, { event -> } as AgentProgressListener, ChildEnvAllowlist.none(), LAW)

        when:
        executor.execute(requestFor(workspaceDir))

        then:
        reportPath.toFile().text.trim() == 'present'
    }
}
