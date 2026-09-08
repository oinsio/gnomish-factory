package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.serve.FeedAutomaton
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * Shared scaffolding for {@link ServeObservabilityIntegrationSpec} and its
 * companion {@link ServeObservabilityRestartIntegrationSpec} (split apart
 * per process-invariants.md's file-size cap, see both classes' javadoc):
 * the minimal {@code .gnomish/} project fixture a real {@link ServeCommand}
 * run needs, the ledger/snapshot JSON readers, and the drain-only {@link
 * FeedAutomatonStarter} stub that fails loudly if drain ever reaches the
 * forever-loop starter.
 *
 * <p>Also owns {@link #newDrainCommand}, the {@link ServeCommand} construction the two specs
 * above built identically before this extraction: a single-attempt drain over the given
 * factory properties, assembly, worktrees/home directories, and tracker adapter factory. Takes
 * the assembly and factory properties as parameters — rather than implementing {@code
 * AppAssemblyFixture} itself — because both specs already mix that trait in directly; a second,
 * transitive implementation here would make Groovy see two independent copies of its default
 * methods and report every one of them as a clashing-method warning.
 *
 * <p>Implements FR9 of add-serve-observability.
 */
trait ServeObservabilityFixture {

    private static final ObjectMapper MAPPER = new ObjectMapper()

    /** Drain never drives the forever-loop starter; fails loudly if that ever changes. */
    static class RefusingStarter implements FeedAutomatonStarter {
        @Override
        void start(FeedAutomaton automaton) {
            throw new IllegalStateException('drain must never use the forever-loop starter')
        }
    }

    /**
     * Builds a {@link ServeCommand} wired for a single-attempt drain pass, sharing the one
     * collaborator set {@link ServeObservabilityIntegrationSpec} and {@link
     * ServeObservabilityRestartIntegrationSpec} both need: a real {@link TaskGitFixture}, the
     * given {@code trackerFactory} behind the {@code github} provider, and a {@link
     * RefusingStarter} that fails loudly if drain ever reaches the forever-loop path. The caller
     * builds {@code assembly} and {@code factoryProperties} itself via its own {@code
     * AppAssemblyFixture} mix-in (see class javadoc for why this trait does not mix it in too).
     */
    ServeCommand newDrainCommand(
            FactoryProperties factoryProperties,
            ManualRunAssembly assembly,
            Path worktreesRoot,
            Path homeDir,
            TrackerAdapterFactory trackerFactory) {
        new ServeCommand(
                assembly,
                TaskGitFixture.real(),
                worktreesRoot,
                homeDir,
                'taskId',
                factoryProperties,
                new ServeProperties(1, null, null, null, null, null, null, null, null),
                Clock.systemUTC(),
                new SystemClock(),
                [github: trackerFactory],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(),
                new RefusingStarter(), SandboxLifecyclePass.NONE, ContainerTakeSupport.hostOnly(),
                new ClaimEpochBook())
    }

    /**
     * Writes the minimal {@code .gnomish/} pipeline (a single {@code build}
     * stage) and config a real {@link ServeCommand} run needs under {@code
     * projectDir}. {@code heartbeatInterval}, when given (e.g. {@code
     * '100ms'}), is written as the tracker's {@code heartbeat-interval}
     * override — {@link ServeRestartIntegrationSpec} needs a short TTL to
     * keep its restart scenario fast; the drain specs above leave it unset.
     */
    static void writeMinimalProject(Path projectDir, String heartbeatInterval = null) {
        Files.createDirectories(projectDir.resolve('.gnomish/stages/build'))
        Files.writeString(projectDir.resolve('.gnomish/pipeline.yaml'), 'stages:\n  - build\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/instructions.md'), 'build it\n')
        Files.writeString(projectDir.resolve('.gnomish/stages/build/stage.yaml'), '''\
purpose: build it
executor:
  type: agent-cli
  model: model-x
instructions: stages/build/instructions.md
advancement: auto
''')
        def heartbeatLine = heartbeatInterval ? "  heartbeat-interval: $heartbeatInterval\n" : ''
        Files.writeString(projectDir.resolve('.gnomish/config.yaml'), '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: https://api.github.com
    repo: acme/widgets
''' + heartbeatLine)
    }

    static JsonNode readJson(Path file) {
        MAPPER.readTree(file.toFile())
    }

    static List<JsonNode> readLedgerLines(Path ledgerFile) {
        ledgerFile.toFile().readLines('UTF-8').findAll {
            !it.isBlank()
        }.collect {
            MAPPER.readTree(it)
        }
    }

    static String instanceIdOf(JsonNode line) {
        line.get('instance').get('instanceId').asText()
    }
}
