package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.SandboxProperties
import com.github.oinsio.gnomish.adapter.check.PinCheckedExternalCheckClient
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckTokenException
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import spock.lang.Specification

/**
 * {@link ManualRunAssembly#assemble} wiring assertions that need no engine round and so no agent
 * subprocess: the starting stage's attempt limit seeded into the {@link
 * com.github.oinsio.gnomish.status.StatusSnapshotHolder}, and the optional heartbeat {@code
 * extraListener} (task 6.1 of add-claim-heartbeat) joined to the engine event composite. Kept apart
 * from {@code ManualRunAssemblySpec}, whose features drive the real fake-agent binary, so these
 * fast checks feed the mutation gate directly.
 *
 * <p>Implements FR11 of add-claim-heartbeat (extra-listener wiring); FR10, D6 of add-agent-executor
 * (attempt-limit seeding).
 */
class ManualRunAssemblyWiringSpec extends Specification implements AppAssemblyFixture {

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
        // Default attempt limit 7, but the 'build' stage overrides to 4 — so the starting-stage
        // limit and the pipeline default are distinct and a swap between them is observable.
        new PipelineDefinition('1', new AutonomyLimits(7), [stage('build', 4)])
    }

    private static TaskContext context() {
        new TaskContext('task-1', 'title', 'body', List.<Decision> of())
    }

    private def assemble(TaskState initialState, EngineEventListener extraListener = null) {
        def assembly = extraListener == null ? newAssembly() : newAssembly().withExtraListener(extraListener)
        assembly.assemble(
                definition(),
                context(),
                initialState,
                RunArguments.InteractiveMode.NONE,
                new InMemoryAttemptPersistence(),
                [],
                // No round runs in this spec, so the law source is never read; any path suffices.
                java.nio.file.Path.of('.'))
    }

    // FR10, D6: the holder is seeded with the STARTING stage's own attempt limit when the position
    //     names a stage present in the pipeline (4 for 'build', not the pipeline default 7).
    def "seeds the status holder with the starting stage's attempt limit"() {
        expect:
        assemble(TaskState.atStageStart('build')).holder().attemptLimit() == 4
    }

    // FR10, D6: when the position names a stage NOT in the pipeline, the holder falls back to the
    //     pipeline default attempt limit (7) rather than the stage limit or zero.
    def "falls back to the pipeline default attempt limit for a stage absent from the pipeline"() {
        expect:
        assemble(TaskState.atStageStart('ghost')).holder().attemptLimit() == 7
    }

    // FR11 of add-claim-heartbeat: a supplied extra listener (the take run's HeartbeatProgress) is
    //     joined to the engine event composite, so every event the engine emits reaches it too.
    def "an extra engine listener is fanned every event through the wired composite"() {
        given:
        def listener = Mock(EngineEventListener)
        def event = new EngineEvent.AttemptStarted(new AttemptKey('task-1', 'build', 0))

        when:
        def run = assemble(TaskState.atStageStart('build'), listener)
        run.ports().listener().onEvent(event)

        then:
        1 * listener.onEvent(event)
    }

    // FR11: with no extra listener supplied, the composite carries only the default listeners — a
    //     null extraListener is never added, so no spurious observer is wired.
    def "no extra listener is wired when none is supplied"() {
        given:
        def event = new EngineEvent.AttemptStarted(new AttemptKey('task-1', 'build', 0))

        when: 'assembling without an extra listener and firing an event does not fail'
        def run = assemble(TaskState.atStageStart('build'))
        run.ports().listener().onEvent(event)

        then:
        noExceptionThrown()
    }
    private static FactoryProperties githubCheckProperties() {
        new FactoryProperties(null, null, null, null,
                new FactoryProperties.Check(new FactoryProperties.Check.Github('https://api.github.com', 'acme/widgets')))
    }

    // FR16, D10, task 8.4 of add-sandbox-core: every assembly binds its external-check client
    //     behind the pin-check guard — the interactive default included.
    def "the default external-check client is wrapped by the pin-check guard"() {
        expect:
        assemble(TaskState.atStageStart('build')).ports().externalClient() instanceof PinCheckedExternalCheckClient
    }

    // FR26 of add-sandbox-core: with factory.check.github.* configured, the GitHub Actions
    //     adapter is built from config with the token resolved by name through the SecretsProvider.
    def "a configured github check binding resolves the token through the SecretsProvider"() {
        given:
        def resolved = []
        def factory = new GithubCheckClientFactory({ name ->
            resolved << name
            Optional.of('tok')
        } as com.github.oinsio.gnomish.app.port.secrets.SecretsProvider)
        def assembly = newAssembly(githubCheckProperties())
        def console = assembly.dialogConsole(context(), TaskState.atStageStart('build'))

        when:
        def client = assembly.externalCheckClient(console, java.nio.file.Path.of('.'), factory)

        then:
        client instanceof PinCheckedExternalCheckClient
        resolved == [
            GithubCheckClientFactory.TOKEN_ENV_VAR
        ]
    }

    // FR26 of add-sandbox-core: a token that does not resolve fails the assembly at wiring time,
    //     naming the missing secret — no stage runs with an unauthenticated adapter.
    def "a configured github check binding with no resolvable token fails the assembly"() {
        when:
        newAssembly(githubCheckProperties()).assemble(
                definition(),
                context(),
                TaskState.atStageStart('build'),
                RunArguments.InteractiveMode.NONE,
                new InMemoryAttemptPersistence(),
                [],
                java.nio.file.Path.of('.'))

        then:
        def e = thrown(GithubCheckTokenException)
        e.message.contains('GNOMISH_GITHUB_ACTIONS_TOKEN')
    }

    // FR26, NFR-S1 of add-sandbox-core: the configured adapter declares its credential name, so
    //     listing it as child-env passthrough fails the assembly naming the variable — the same
    //     treatment the tracker token gets.
    def "the external-check token cannot be allowlisted as passthrough when the adapter is configured"() {
        given: 'an assembly whose operator passthrough lists the check token name'
        def assembly = new ManualRunAssembly(
                new com.github.oinsio.gnomish.adapter.console.SystemConsoleIO(
                new ByteArrayInputStream(new byte[0]), System.out),
                new com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner(),
                new com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner(),
                new com.github.oinsio.gnomish.adapter.engine.SystemClock(),
                new com.github.oinsio.gnomish.adapter.engine.ThreadSleeper(),
                githubCheckProperties(),
                new SandboxProperties(null, null, null, null, null, [
                    GithubCheckClientFactory.TOKEN_ENV_VAR
                ], false))

        when:
        assembly.assemble(
                definition(),
                context(),
                TaskState.atStageStart('build'),
                RunArguments.InteractiveMode.NONE,
                new InMemoryAttemptPersistence(),
                [],
                java.nio.file.Path.of('.'))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(GithubCheckClientFactory.TOKEN_ENV_VAR)
    }
}
