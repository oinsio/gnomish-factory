package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitVersionCheck
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.sandbox.DiscoveredBindings
import com.github.oinsio.gnomish.adapter.secrets.EnvFileSecretsProvider
import com.github.oinsio.gnomish.adapter.tracker.FixedTrackerAdapterFactory
import com.github.oinsio.gnomish.app.console.SystemConsoleIO
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.app.project.ProjectRegistry
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.environment.DockerRuntimeProbe
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.function.BooleanSupplier
import java.util.function.Supplier
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.core.env.StandardEnvironment

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
                // The error console the composition root binds to System.err; a spec capturing the
                // run's output reads `output` above, and the terminal error paths stay on stderr.
                new SystemConsoleIO(new ByteArrayInputStream(new byte[0]), System.err),
                new CheckEquipment(
                        new FilesExistCheckRunner(),
                        new ShellCommandCheckRunner(),
                        [(GithubCheckClientFactory.PROVIDER): new GithubCheckClientFactory()],
                        // An empty factory home of its own: no secrets folder, so every secret resolves from
                        // the environment exactly as before the folders existed (FR8 of add-project-registry).
                        new EnvFileSecretsProvider(FactoryHome.at(Files.createTempDirectory('no-secrets-home')), null),
                        factoryProperties),
                new SystemClock(),
                new ThreadSleeper(),
                factoryProperties,
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null))
                // FR13, D14 of add-base-ref-resolution: production wiring (TakeCommand/ServeCommand)
                // always attaches the pipeline source it read its startup definition from before a
                // fresh claim can read its task tier through it; a spec building this assembly
                // directly (bypassing TakeCommand) needs the same real source so TaskTierLaw#bind
                // never sees "no PipelineSource attached" as a wiring fault. The accept-anything
                // 'github' registry matches every fixture's own tracker: section (design D15's
                // pre-wiring-seam permissiveness), never validating GitHub subsection content.
                .withPipelineSource(TrackerValidatorStub.acceptingGithubSource())
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
     * Builds a fresh {@link ManualRunRunner} from its standard collaborator
     * set, shared by every composition-root spec that constructs the runner
     * directly rather than through {@link #newAssembly}. {@code
     * sandboxProperties} and {@code bindingProperties} are the two arguments
     * specs legitimately vary (a container image, a non-host binding mode);
     * everything else is the dominant literal every call site used to repeat
     * verbatim.
     *
     * <p>{@code factoryProperties} and {@code boardWiring} default to the identity literals
     * every prior call site used inline; a spec that needs a non-default instance name (fed to
     * both the runner and its embedded {@link BoardCommand}/{@link DashboardCommand}) or a
     * board over a fake tracker (e.g. one wired to an outage tracker) supplies its own. The board
     * itself is built here, over the same registered clone every other command works in (FR3 of
     * add-project-registry).
     *
     * <p>Implements FR1, FR2 of add-serve-sandbox-lifecycle.
     */
    ManualRunRunner newManualRunRunner(
            Path clonePath,
            Path homeDir,
            SandboxProperties sandboxProperties = new SandboxProperties(
                    null, null, null, null, null, null, false, null, null, null, null),
            // Host binding, explicitly: container is the default (D13 of add-sandbox-core),
            // and most specs sharing this fixture prove the host git-mode path.
            BindingProperties bindingProperties = new BindingProperties('host', [:]),
            // Defaulted last so every existing call site is untouched: only the specs that need to
            // tell the runner's own git bundle apart from the identity default supply their own.
            TaskGit git = TaskGitFixture.real(),
            FactoryProperties factoryProperties = testProperties(),
            TrackerWiring boardWiring = new TrackerWiring([:], MapSecretsProvider.NONE, TrackerValidatorStub.plainSource()),
            // The tracker registry the dispatch hands `take`/`serve`; empty for the host git-mode
            // specs, which never reach a tracker.
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry = [:],
            // FR10 of own-git-transfer-argv: the floor check every command passes through first.
            // The default reads the developer's real git, as production does; a spec proving the
            // refusal hands in a check over a fake git (GitVersionFloorSpec).
            GitVersionCheck gitVersionCheck = new GitVersionCheck(new GitProcessRunner())) {
        buildManualRunRunner(clonePath, homeDir, sandboxProperties, bindingProperties, git, factoryProperties,
                boardWiring, trackerAdapterRegistry, gitVersionCheck, DockerRuntimeProbe.&dockerAvailable as BooleanSupplier)
    }

    /**
     * As {@link #newManualRunRunner} with every default, over a scripted container prerequisite
     * probe (D13 of add-sandbox-core) — the runner a daemon-free container dispatch spec drives,
     * through the test constructor of {@link ContainerSupports} rather than a field write.
     */
    ManualRunRunner newManualRunRunnerProbing(Path clonePath, Path homeDir, SandboxProperties sandboxProperties,
            BindingProperties bindingProperties, BooleanSupplier dockerProbe) {
        def factoryProperties = testProperties()
        buildManualRunRunner(clonePath, homeDir, sandboxProperties, bindingProperties, TaskGitFixture.real(),
                factoryProperties,
                new TrackerWiring([:], MapSecretsProvider.NONE, TrackerValidatorStub.plainSource()),
                [:], new GitVersionCheck(new GitProcessRunner()), dockerProbe)
    }

    private ManualRunRunner buildManualRunRunner(Path clonePath, Path homeDir, SandboxProperties sandboxProperties,
            BindingProperties bindingProperties, TaskGit git, FactoryProperties factoryProperties, TrackerWiring boardWiring,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry, GitVersionCheck gitVersionCheck,
            BooleanSupplier dockerProbe) {
        // FR2, FR4 of add-project-registry: the project registry under a home of the spec's own.
        def projectHome = FactoryHome.at(homeDir.resolve('.gnomish'))
        // FR9, FR10 of add-project-registry: the clone the spec's commands work in — its worktree
        // folder, its serve directory — registered through the production registry on first read,
        // as the loader resolves --dir only once a command runs (the spec may make clonePath a git
        // working tree after building the runner).
        // A spec whose clonePath never becomes a git working tree (an in-place run, a usage error, a
        // board over a bare .gnomish/ tree) gets the unregistered value: the loader that would refuse
        // it is not part of this graph.
        def resolvedClone = RegisteredCloneFixture.lazy {
            Files.exists(clonePath.resolve('.git'))
            ? RegisteredCloneFixture.resolvedOrRegistered(projectHome.root(), clonePath)
            : RegisteredCloneFixture.unregistered(projectHome.root(), clonePath)
        }
        // FR3, FR10 of add-project-registry: every project-scoped command takes the clone through
        // the production ProjectScope, as the context wires it.
        def scope = new ProjectScope(resolvedClone, factoryProperties)
        def boardCommand = new BoardCommand(Clock.systemUTC(), factoryProperties, scope, boardWiring, LiveConsoleIO.onStdout())
        def console = new SystemConsoleIO(System.in, System.out)
        // The error console the composition root binds to System.err (FR6 of
        // harden-untrusted-text-sinks); the specs that assert on it redirect that stream.
        def errorConsole = LiveConsoleIO.onStderr()
        def checkClientRegistry = [(GithubCheckClientFactory.PROVIDER): new GithubCheckClientFactory()]
        def systemClock = new SystemClock()
        // The assembly the context's manualRunAssembly bean builds (design D3 of
        // collapse-composition-roots): the same instances the runner itself receives below.
        def assembly = new ManualRunAssembly(
                console,
                errorConsole,
                new CheckEquipment(
                        new FilesExistCheckRunner(),
                        new ShellCommandCheckRunner(),
                        checkClientRegistry,
                        MapSecretsProvider.NONE,
                        factoryProperties),
                systemClock,
                new ThreadSleeper(),
                factoryProperties,
                sandboxProperties)
        // The graph the context assembles (design D6, D11 of collapse-composition-roots), built
        // through the production bean methods themselves so the fixture cannot drift from them.
        def manualRun = new ManualRunConfiguration()
        def commands = new TrackerCommandConfiguration()
        def containerSupports = new ContainerSupports(checkClientRegistry, factoryProperties, sandboxProperties,
                bindingProperties, DiscoveredBindings.real(), git, dockerProbe)
        def trackerWiring = new TrackerWiring(trackerAdapterRegistry, MapSecretsProvider.NONE, TrackerValidatorStub.plainSource())
        def serveProperties = new ServeProperties(0, null, null, null, null, null, null, null, null)
        def javaTimeClock = Clock.systemUTC()
        def sandboxLifecyclePass = commands.sandboxLifecyclePass(sandboxProperties, factoryProperties, javaTimeClock)
        def slotWiringFactory = commands.slotWiringFactory(assembly, resolvedClone, javaTimeClock, containerSupports, trackerWiring)
        def reportCommands = new ReportCommands(
                new StatusCommand(TaskGitFixture.realClaimless(), scope, LiveConsoleIO.onStdout()),
                new UsageCommand(TaskGitFixture.realClaimless(), scope, LiveConsoleIO.onStdout()),
                boardCommand,
                new DashboardCommand(Clock.systemUTC(), new ThreadSleeper(), scope, factoryProperties, new TrackerWiring([:], MapSecretsProvider.NONE, TrackerValidatorStub.plainSource())))
        def dispatch = commands.subcommandDispatch(
                reportCommands,
                commands.takeCommand(slotWiringFactory, git, factoryProperties, scope, trackerWiring,
                commands.takeCommandSeams(serveProperties, javaTimeClock), sandboxLifecyclePass),
                commands.serveCommand(
                        commands.serveRuntimeAssembly(slotWiringFactory,
                        commands.serveAssembly(factoryProperties, serveProperties, systemClock, resolvedClone), git, javaTimeClock,
                        sandboxLifecyclePass, sandboxProperties),
                        git, scope, serveProperties, trackerWiring, errorConsole))
        def drive = manualRun.manualRunDrive(
                scope,
                new PipelineStartup(TrackerValidatorStub.plainSource()),
                new AdHocTaskSynthesizer(Clock.systemUTC(), new Random()),
                assembly,
                new InMemoryAttemptPersistence(),
                console,
                lazyRunners {
                    manualRun.manualRunners(assembly, git, resolvedClone.getObject(), containerSupports, console)
                })
        def projectCommand = new ProjectCommand(projectHome, ProjectRegistry.scan(projectHome), factoryProperties,
                new StandardEnvironment(), new DefaultListableBeanFactory().getBeanProvider(RegisteredClone), console)
        new ManualRunRunner(gitVersionCheck, dispatch, projectCommand, drive, errorConsole)
    }

    /** The runners as the drive's lazily read provider yields them: built on the first git-mode run. */
    private ObjectProvider<ManualRunners> lazyRunners(Supplier<ManualRunners> runners) {
        def beans = new DefaultListableBeanFactory()
        beans.registerBeanDefinition('manualRunners', new RootBeanDefinition(ManualRunners, runners))
        beans.getBeanProvider(ManualRunners)
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
     * Runs a {@link TakeCommand}, asserting it exits via {@link TakeExitCodeException} (the
     * command's only normal-exit path) and returning that exception's code — shared by the
     * two-instance lifecycle specs that assert on {@code take}'s exit code rather than its thrown
     * type.
     */
    static int runExitCode(TakeCommand command, DefaultApplicationArguments appArgs) {
        try {
            command.run(appArgs)
            throw new IllegalStateException('take did not exit with a TakeExitCodeException')
        } catch (TakeExitCodeException e) {
            e.exitCode()
        }
    }

    /**
     * A {@link TrackerTask} in the given {@code state}, wrapping a plain
     * {@code title}/{@code body} {@link TaskSnapshot} and no abort history —
     * the dominant shape {@code fetchTask} stubs return across the batch and
     * dispatcher specs sharing this fixture.
     */
    static TrackerTask trackerTask(TaskRef ref, TrackerTaskState state, String taskId) {
        new TrackerTask(ref, new TaskSnapshot(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body')), state, AbortFacts.none(), false)
    }

    /**
     * A plain {@link TaskContext} with a fixed title/body and no decisions — the bare test
     * context every app-layer spec that only needs a valid, otherwise-inert one reaches for.
     * Extracted (rule of three, {@code .claude/rules/manual-sync-pairs.md}) from four identical
     * private copies: {@code RunnerStartHardeningSpec}, {@code ContainerGitModeRunnerSpec},
     * {@code GiteaBestEffortPushE2ESpec}, {@code GiteaCrossInstanceResumeE2ESpec}.
     */
    static TaskContext context(String taskId) {
        new TaskContext(taskId, UntrustedText.tracker('title'), UntrustedText.tracker('body'), List.<Decision> of())
    }
}
