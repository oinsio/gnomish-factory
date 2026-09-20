package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.PinCheckedExternalCheckClient
import com.github.oinsio.gnomish.adapter.check.ProviderDispatchingExternalCheckClient
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.adapter.secrets.EnvFileSecretsProvider
import com.github.oinsio.gnomish.app.console.SystemConsoleIO
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Path
import spock.lang.Specification

/**
 * {@link ManualRunAssembly#assemble} wiring assertions for the external-check client: the
 * pin-check guard that wraps every assembly's client, the provider-dispatching composite a
 * configured {@code factory.check.<provider>} subsection puts behind that guard, and the
 * check-provider SPI's credential-name guard. Needs no engine round and so no agent subprocess.
 * Split out of {@code ManualRunAssemblyWiringSpec} (whose own doc comment only ever covered
 * attempt-limit seeding and extra-listener wiring) to keep one capability per spec file
 * ({@code .claude/rules/testing.md}) and the file under the 200-line cap
 * ({@code .claude/rules/process-invariants.md}).
 *
 * <p>Implements FR16, D10, task 8.4 of add-sandbox-core (pin-check guard); FR5, FR6, D10 of
 * add-plugin-architecture (provider-dispatching composite); FR3 of add-plugin-architecture
 * (lazy credential resolution); FR17, D11 of add-plugin-architecture (passthrough guard).
 */
class ManualRunAssemblyCheckClientWiringSpec extends Specification implements AppAssemblyFixture {

    private static StageDefinition stage(String name, int attemptLimit) {
        new StageDefinition(
                name,
                'purpose',
                [],
                [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md',
                [],
                new AutonomyLimits(attemptLimit),
                AdvancementMode.AUTO)
    }

    private static PipelineDefinition definition() {
        new PipelineDefinition('1', new AutonomyLimits(7), [stage('build', 4)])
    }

    private def assemble(TaskState initialState) {
        newAssembly().assemble(
                definition(),
                context('task-1'),
                initialState,
                RunArguments.InteractiveMode.NONE,
                new InMemoryAttemptPersistence(),
                [],
                // No round runs in this spec, so the law source is never read; any binding suffices.
                LawBinding.workingTree(Path.of('.')))
    }

    private static FactoryProperties githubCheckProperties() {
        new FactoryProperties(null, null, null, null,
                [(GithubCheckClientFactory.PROVIDER): [('api-url'): 'https://api.github.com', repo: 'acme/widgets']])
    }

    private static Map<String, CheckClientFactory> githubRegistry() {
        [(GithubCheckClientFactory.PROVIDER): new GithubCheckClientFactory()]
    }

    // FR16, D10, task 8.4 of add-sandbox-core: every assembly binds its external-check client
    //     behind the pin-check guard — the interactive default included.
    def "the default external-check client is wrapped by the pin-check guard"() {
        expect:
        assemble(TaskState.atStageStart('build')).ports().externalClient() instanceof PinCheckedExternalCheckClient
    }

    // FR5, FR6, D10 of add-plugin-architecture: with any factory.check.<provider> subsection
    //     configured, the seam behind the guard is the provider-dispatching composite — the engine
    //     port is unchanged, and which provider answers is decided per check rather than at wiring.
    def "a configured check provider puts the dispatching composite behind the guard"() {
        given:
        def assembly = newAssembly(githubCheckProperties())
        def console = assembly.dialogConsole(context('task-1'), TaskState.atStageStart('build'))

        when:
        def client = assembly.externalCheckClient(console, LawBinding.workingTree(Path.of('.')), githubRegistry())

        then:
        client instanceof PinCheckedExternalCheckClient
        client.delegate() instanceof ProviderDispatchingExternalCheckClient
    }

    // FR3 of add-plugin-architecture: a configured provider's client is built lazily, on first
    //     selection — assembling resolves no credential, so a provider no check ever selects stays
    //     entirely unexercised. (Before providers existed the token resolved at wiring time and a
    //     missing one failed the assembly; the fail-closed moment now sits at first poll instead.)
    def "assembling a configured check provider resolves no credential"() {
        given:
        def resolved = []
        def assembly = new ManualRunAssembly(
                new SystemConsoleIO(
                        new ByteArrayInputStream(new byte[0]), System.out),
                new SystemConsoleIO(new ByteArrayInputStream(new byte[0]), System.err),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                githubRegistry(), { name ->
                    resolved << name; Optional.of('tok')
                } as SecretsProvider,
                new SystemClock(),
                new ThreadSleeper(),
                githubCheckProperties(),
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null))

        when:
        assembly.assemble(
                definition(),
                context('task-1'),
                TaskState.atStageStart('build'),
                RunArguments.InteractiveMode.NONE,
                new InMemoryAttemptPersistence(),
                [],
                LawBinding.workingTree(Path.of('.')))

        then:
        resolved.isEmpty()
    }

    // FR17, D11 of add-plugin-architecture: the credential names come from the configured
    //     providers' own SPI declarations, so listing one as child-env passthrough fails the
    //     assembly naming the variable — with no core source naming that variable.
    def "a discovered provider's declared credential cannot be allowlisted as passthrough"() {
        given: 'an assembly whose operator passthrough lists the check provider\'s credential name'
        def assembly = new ManualRunAssembly(
                new SystemConsoleIO(
                        new ByteArrayInputStream(new byte[0]), System.out),
                new SystemConsoleIO(new ByteArrayInputStream(new byte[0]), System.err),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                githubRegistry(),
                new EnvFileSecretsProvider(),
                new SystemClock(),
                new ThreadSleeper(),
                githubCheckProperties(),
                new SandboxProperties(null, null, null, null, null, [
                    GithubCheckClientFactory.TOKEN_ENV_VAR
                ], false, null, null, null, null))

        when:
        assembly.assemble(
                definition(),
                context('task-1'),
                TaskState.atStageStart('build'),
                RunArguments.InteractiveMode.NONE,
                new InMemoryAttemptPersistence(),
                [],
                LawBinding.workingTree(Path.of('.')))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(GithubCheckClientFactory.TOKEN_ENV_VAR)
    }
}
