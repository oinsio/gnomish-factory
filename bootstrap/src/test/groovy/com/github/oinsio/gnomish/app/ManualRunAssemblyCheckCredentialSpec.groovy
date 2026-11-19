package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.adapter.scm.actions.GithubActionsExecutor
import com.github.oinsio.gnomish.adapter.secrets.EnvFileSecretsProvider
import com.github.oinsio.gnomish.app.console.SystemConsoleIO
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification

/**
 * {@link ManualRunAssembly#assemble} and the built-ins' credential names: assembling resolves no
 * credential, and a name the run's scrub and never-allowlist sets hold — a built-in's token name, or
 * the credential an {@code http} check names in its own settings — refused as operator passthrough.
 * The names come from their one owner, {@link BuiltInCheckSubsections}, which replaced the
 * check-provider SPI's declarations (task 5.2 of migrate-verify-to-executor-contract). Needs no
 * engine round and so no agent subprocess.
 *
 * <p>Implements FR3, FR17, D11 of add-plugin-architecture; NFR-S1 of
 * migrate-verify-to-executor-contract.
 */
class ManualRunAssemblyCheckCredentialSpec extends Specification implements AppAssemblyFixture {

    private static StageDefinition stage(String name, int attemptLimit, List<VerifyCheck> checks) {
        new StageDefinition(
                name,
                'purpose',
                [],
                [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md',
                checks,
                new AutonomyLimits(attemptLimit),
                AdvancementMode.AUTO)
    }

    private static PipelineDefinition definition(List<VerifyCheck> checks = []) {
        new PipelineDefinition('1', new AutonomyLimits(7), [stage('build', 4, checks)])
    }

    private static FactoryProperties githubCheckProperties() {
        new FactoryProperties(null, null, null,
                [('github-actions'): [('api-url'): 'https://api.github.com', repo: 'acme/widgets']])
    }

    private static ManualRunAssembly assemblyOver(FactoryProperties properties, SecretsProvider secrets, List<String> passthrough = []) {
        // FR21 of supervise-daemon-loops-and-embed-dashboard: one virtual equipment for the assembly.
        def time = VirtualTimeEquipment.create()
        new ManualRunAssembly(
                new SystemConsoleIO(new ByteArrayInputStream(new byte[0]), System.out),
                new SystemConsoleIO(new ByteArrayInputStream(new byte[0]), System.err),
                new CheckEquipment(new FilesExistCheckRunner(), new ShellCommandCheckRunner(time.clock()), secrets, properties),
                time,
                properties,
                new SandboxProperties(null, null, null, null, passthrough, false, null, null, null, null),
                new DirectoryWorkspaceFactory())
    }

    // FR3 of add-plugin-architecture: a connected built-in no check names stays entirely
    //     unexercised — assembling resolves no credential (task 5.2 of
    //     migrate-verify-to-executor-contract: its entry is built only for a law that names it).
    def "assembling with a connected built-in no check names resolves no credential"() {
        given:
        def resolved = []
        def assembly = assemblyOver(githubCheckProperties(), { name ->
            resolved << name; Optional.of('tok')
        } as SecretsProvider)

        when:
        assembly.assemble(
                new RunOrder(Path.of('').toAbsolutePath(), null, definition(), false),
                context('task-1'),
                TaskState.atStageStart('build'),
                new InMemoryAttemptPersistence(),
                [],
                new DescribedLaw(LawBinding.workingTree(Path.of('').toAbsolutePath()), [:]))

        then:
        resolved.isEmpty()
    }

    // FR17, D11 of add-plugin-architecture: a built-in's token name is in the run's declared set
    //     whether or not a check names it, so listing it as child-env passthrough fails the
    //     assembly naming the variable; and so does the credential an http check names in its own
    //     settings, which its describe cannot declare (task 5.2 of migrate-verify-to-executor-contract).
    // Scenario: sandbox/execution-environment / A credential passthrough name fails startup
    // Scenario: verification/github-external-check / External-check token cannot be allowlisted
    def "a #what cannot be allowlisted as passthrough"() {
        given: 'an assembly whose operator passthrough lists the credential name'
        def assembly = assemblyOver(githubCheckProperties(),
                // An empty factory home of its own: no secrets folder, so every secret resolves from
                // the environment exactly as before the folders existed (FR8 of add-project-registry).
                new EnvFileSecretsProvider(FactoryHome.at(Files.createTempDirectory('no-secrets-home')), null),
                [name])

        when:
        assembly.assemble(
                new RunOrder(Path.of('').toAbsolutePath(), null, definition(checks), false),
                context('task-1'),
                TaskState.atStageStart('build'),
                new InMemoryAttemptPersistence(),
                [],
                new DescribedLaw(LawBinding.workingTree(Path.of('').toAbsolutePath()), [:]))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(name)

        where:
        what | name | checks
        "built-in's token name" | GithubActionsExecutor.TOKEN_ENV_VAR | []
        "credential an http check names" | 'GNOMISH_SONAR_TOKEN' | [
            httpCheck('GNOMISH_SONAR_TOKEN')
        ]
    }

    private static VerifyCheck.Executor httpCheck(String credential) {
        VerifyChecks.external('quality-gate', 'http', [url: 'https://ci.example.invalid/status',
            auth: [credential: credential]], Duration.ofSeconds(1), Duration.ofSeconds(30),
        VerifyCheck.TimeoutClass.QUALITY, [])
    }
}
