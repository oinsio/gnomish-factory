package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.fake.FakeAgentBinary
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
 * D17, NFR-S1 of add-tracker-port (task 5.17): {@link CliStageExecutor}'s four-argument
 * constructor threads {@code credentialEnvVarsToScrub} into its own {@link
 * AgentProcessLauncher} — a focused unit-level proof, one rung below {@code
 * TakeCommandCredentialScrubSpec}'s full {@code take}-flavored end-to-end wiring proof.
 */
class CliStageExecutorCredentialScrubSpec extends Specification {

    private static final String CREDENTIAL_VAR = 'HOME'

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
        new FactoryProperties('factory-01', wrapper.absolutePath, [], null)
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

    def "the four-argument constructor's scrub list reaches the spawned process"() {
        given:
        def executor = new CliStageExecutor(wrapperReporting(), clock, { event -> } as AgentProgressListener, [CREDENTIAL_VAR])

        when:
        executor.execute(requestFor(workspaceDir))

        then:
        reportPath.toFile().text.trim() == 'absent'
    }

    def "with nothing declared to scrub, the same variable reaches the spawned process"() {
        given:
        def executor = new CliStageExecutor(wrapperReporting(), clock, { event -> } as AgentProgressListener, [])

        when:
        executor.execute(requestFor(workspaceDir))

        then:
        reportPath.toFile().text.trim() == 'present'
    }
}
