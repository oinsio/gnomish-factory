package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.sandbox.DiscoveredBindings
import com.github.oinsio.gnomish.adapter.secrets.EnvFileSecretsProvider
import com.github.oinsio.gnomish.adapter.tracker.FixedTrackerAdapterFactory
import com.github.oinsio.gnomish.app.console.SystemConsoleIO
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Path
import java.time.Clock
import org.springframework.boot.DefaultApplicationArguments

/**
 * Shared factory methods for the one construction block twenty-one app-layer
 * spec files used to inline: a {@link ManualRunAssembly} built from the
 * standard 6-collaborator set, and the {@link FactoryProperties} value most
 * of them pass in. A plain Groovy trait, not a base class and not Spring
 * (design D1) — every collaborator here is a cheap stateless {@code new}, so
 * a Spring test context would only add latency (NFR-P1) for no benefit.
 *
 * <p>Defaults mirror today's dominant literal values exactly so a spec that
 * takes every default reads the same as before; any genuine deviation
 * (custom console streams, a fake-agent binary, an explicit instance name)
 * is a named argument at the call site, keeping intent visible in review
 * diffs (UX1). {@link ManualRunAssembly}'s own public constructor stays
 * available for specs whose subject under test is construction itself (e.g.
 * {@code ManualRunAssemblySpec}) or that need a collaborator this trait does
 * not cover.
 *
 * <p>Implements FR1, FR2 of refactor-app-spec-fixtures.
 */
trait AppAssemblyFixture implements FactoryPropertiesFixture {

    /**
     * Builds a fresh {@link ManualRunAssembly} from the standard
     * 6-collaborator set: {@link SystemConsoleIO} (over {@code input}/
     * {@code output}), {@link FilesExistCheckRunner}, {@link
     * ShellCommandCheckRunner}, {@link SystemClock}, {@link ThreadSleeper},
     * and {@code factoryProperties}. Every call returns a brand-new
     * instance — no collaborator is cached on the trait (NFR-R1), so two
     * calls from the same spec never share state.
     *
     * <p>{@code input} defaults to twenty platform line separators, the
     * dominant literal at direct call sites: enough buffered newlines for
     * console prompts the test never actually drives interactively.
     *
     * <p>Implements FR1 of refactor-app-spec-fixtures.
     */
    ManualRunAssembly newAssembly(
            InputStream input = null,
            PrintStream output = System.out,
            FactoryProperties factoryProperties = testProperties()) {
        new ManualRunAssembly(
                new SystemConsoleIO(
                        input ?: new ByteArrayInputStream((System.lineSeparator() * 20).getBytes('UTF-8')), output),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                [(GithubCheckClientFactory.PROVIDER): new GithubCheckClientFactory()],
                new EnvFileSecretsProvider(),
                new SystemClock(),
                new ThreadSleeper(),
                factoryProperties,
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null))
    }

    /**
     * Overload for the dominant deviation: a spec needs only a non-default
     * {@link FactoryProperties} (a fake-agent binary, a per-instance name, a
     * custom {@link FactoryProperties.Tracker}) while keeping the standard
     * console streams. Groovy cannot skip the two leading positional defaults
     * of {@link #newAssembly(InputStream, PrintStream, FactoryProperties)}, so
     * without this overload such a site has to repeat both stream defaults
     * verbatim just to reach the third argument — the exact noise this fixture
     * exists to remove (UX1). Lets the call read {@code
     * newAssembly(testProperties(instanceName: NAME))} (design D3).
     *
     * <p>Implements FR1, FR2 of refactor-app-spec-fixtures.
     */
    ManualRunAssembly newAssembly(FactoryProperties factoryProperties) {
        newAssembly(null, System.out, factoryProperties)
    }

    /**
     * Builds a fresh {@link ManualRunRunner} from its standard 21-collaborator
     * set, shared by every composition-root spec that constructs the runner
     * directly rather than through {@link #newAssembly}. {@code
     * sandboxProperties} and {@code bindingProperties} are the two arguments
     * specs legitimately vary (a container image, a non-host binding mode);
     * everything else is the dominant literal every call site used to repeat
     * verbatim.
     *
     * <p>Implements FR1, FR2 of add-serve-sandbox-lifecycle.
     */
    ManualRunRunner newManualRunRunner(
            Path worktreesRoot,
            Path homeDir,
            SandboxProperties sandboxProperties = new SandboxProperties(
                    null, null, null, null, null, null, false, null, null, null, null),
            // Host binding, explicitly: container is the default (D13 of add-sandbox-core),
            // and most specs sharing this fixture prove the host git-mode path.
            BindingProperties bindingProperties = new BindingProperties('host', [:])) {
        new ManualRunRunner(
                new RunArgumentsParser(),
                new PipelineStartup(TrackerValidatorStub.plainSource()),
                new AdHocTaskSynthesizer(Clock.systemUTC(), new Random()),
                new SystemConsoleIO(System.in, System.out),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                [(GithubCheckClientFactory.PROVIDER): new GithubCheckClientFactory()],
                new InMemoryAttemptPersistence(),
                new SystemClock(),
                new ThreadSleeper(),
                testProperties(),
                sandboxProperties,
                bindingProperties,
                DiscoveredBindings.real(),
                TaskGitFixture.real(),
                worktreesRoot,
                homeDir,
                new StatusCommand(TaskGitFixture.real(), worktreesRoot),
                new UsageCommand(TaskGitFixture.real()),
                new BoardCommand(Clock.systemUTC(), testProperties(), [:], MapSecretsProvider.NONE,
                TrackerValidatorStub.plainSource()),
                new DashboardCommand(Clock.systemUTC(), new ThreadSleeper(), homeDir, testProperties(), [:],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.plainSource()),
                Clock.systemUTC(),
                [:],
                MapSecretsProvider.NONE,
                TrackerValidatorStub.plainSource(),
                new ServeProperties(0, null, null, null, null, null, null))
    }

    /**
     * A {@link TrackerAdapterFactory} whose {@code create} always returns the
     * given fake/mock {@code Tracker} and whose {@code expandRef} always
     * throws, since no spec using this fixture exercises short-ref expansion
     * through a real tracker adapter. Delegates to the canonical {@link
     * FixedTrackerAdapterFactory} in the {@code adapter.tracker} package
     * rather than keeping a second definition of the same fixture here.
     */
    static TrackerAdapterFactory fakeFactory(Tracker t) {
        new FixedTrackerAdapterFactory({ t })
    }

    /**
     * Wraps CLI-style {@code String} args as Spring's {@link
     * DefaultApplicationArguments}, shared by the fixtures that build a
     * {@link TakeCommand} and drive it with {@code command.run(...)}.
     */
    static DefaultApplicationArguments takeArgs(String... raw) {
        new DefaultApplicationArguments(raw)
    }

    /**
     * A {@link TrackerTask} in the given {@code state}, wrapping a plain
     * {@code title}/{@code body} {@link TaskSnapshot} and no abort history —
     * the dominant shape {@code fetchTask} stubs return across the batch and
     * dispatcher specs sharing this fixture.
     */
    static TrackerTask trackerTask(TaskRef ref, TrackerTaskState state, String taskId) {
        new TrackerTask(ref, new TaskSnapshot(taskId, 'title', 'body'), state, AbortFacts.none(), false)
    }
}
