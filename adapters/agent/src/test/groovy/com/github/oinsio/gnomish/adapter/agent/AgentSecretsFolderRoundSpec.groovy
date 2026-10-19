package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.adapter.secrets.EnvFileSecretsProvider
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.agent.JudgeEnvironmentSource
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectLayout
import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ChildEnvValues
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NFR-S3, finding F107 of tighten-spec-contract: an agent round and a judge vote read the agent
 * CLI's own token through the secrets port, so a token only the host secrets folder holds reaches
 * every project's rounds — the value every other consumer of the port resolves. The allowlist is
 * built over the real env/file adapter exactly as the composition root binds it ({@code
 * SecretsProvider::find} as the allowlist's values), and the launched commands are recorded at the
 * environment port, where the value leaves the factory.
 *
 * <p>Implements NFR-S3 of tighten-spec-contract; FR8 of add-project-registry.
 */
class AgentSecretsFolderRoundSpec extends Specification {

    private static final String TOKEN = 'CLAUDE_CODE_OAUTH_TOKEN'

    private static final PipelineLaw LAW =
    PipelineLaw.ofContent(['instructions.md': 'Do the thing.', 'criteria.md': 'The output must be correct.'])

    private static final FactoryProperties PROPERTIES =
    new FactoryProperties('factory-01', 'claude', null, null)

    @TempDir
    Path tempDir

    final List<ExecCommand> launched = []

    // NFR-S3 of tighten-spec-contract: the agent round and the judge vote take the token from the
    //     port, for a project, for another project and for no project, and it is the value the
    //     port hands any other consumer
    // Scenario: operator/secrets-provider / Shared agent token from the host folder
    def "NFR-S3: a token only the host secrets folder holds reaches every project's agent round and judge vote"() {
        given: 'the host secrets folder holds the agent token, and no project folder does'
        def home = FactoryHome.at(tempDir.resolve('home'))
        hostSecret(home, 'shared-from-host-folder')
        def projects = [
            home.project(new ProjectName('widgets')),
            home.project(new ProjectName('gadgets')),
            null
        ]

        when: 'each project runs one executor round and one judge vote over the port it resolves through'
        def resolved = projects.collect { ProjectLayout project ->
            def secrets = new EnvFileSecretsProvider(home, project)
            // The root's binding: the port's lookup is the allowlist's values
            def childEnv = ChildEnvAllowlist.of([], [], { String name ->
                secrets.find(name)
            } as ChildEnvValues)
            executorRound(childEnv)
            judgeVote(childEnv)
            secrets.find(TOKEN)
        }

        then: 'every launch carried the host folder token, the same value the port resolves for any consumer'
        resolved.every { it == Optional.of('shared-from-host-folder') }
        launched.size() == 6
        launched.every { it.env()[TOKEN] == 'shared-from-host-folder' }
    }

    private static void hostSecret(FactoryHome home, String value) {
        def file = home.hostSecret(TOKEN)
        Files.createDirectories(file.parent)
        Files.writeString(file, value + '\n')
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString('rw-------'))
    }

    /** A box that records every launch and answers it with the scenario's recorded stdout. */
    private TaskExecutionEnvironment box(String scenario) {
        Stub(TaskExecutionEnvironment) {
            exec(_ as ExecCommand) >> { ExecCommand command ->
                launched << command
                new ExitedExecHandle(CannedOutputs.written(getClass().getResourceAsStream("/fake-agent/scenarios/${scenario}/stdout.jsonl")))
            }
        }
    }

    private void executorRound(ChildEnvAllowlist childEnv) {
        Path workspaceDir = Files.createTempDirectory(tempDir, 'round')
        RoundEnvironmentSource rounds = { req ->
            new StandInRound(box('plain-round'), workspaceDir.resolve('decision.json'))
        }
        new CliStageExecutor(PROPERTIES, new VirtualClock(), { e -> } as AgentProgressListener, childEnv, LAW, rounds)
        .execute(FakeAgentSupport.requestFor(workspaceDir))
    }

    private void judgeVote(ChildEnvAllowlist childEnv) {
        Path workspaceDir = Files.createTempDirectory(tempDir, 'vote')
        JudgeEnvironmentSource judges = { Workspace ws ->
            box('judge-verdict-pass')
        }
        new CliJudgeVoter(PROPERTIES, new VirtualClock(), { e -> } as AgentProgressListener, childEnv, LAW, judges)
        .vote(new VerifyCheck.Judge('criteria.md', 'claude-fake-judge-1', [:], 1),
        FakeAgentSupport.defaultTaskContext(), new DirectoryWorkspace(workspaceDir))
    }
}
