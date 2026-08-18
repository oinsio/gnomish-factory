package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory
import com.github.oinsio.gnomish.adapter.secrets.EnvFileSecretsProvider
import com.github.oinsio.gnomish.adapter.tracker.FixedTrackerAdapterFactory
import com.github.oinsio.gnomish.app.console.SystemConsoleIO
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.engine.time.SystemClock
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper
import com.github.oinsio.gnomish.sandbox.SandboxProperties

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
                new SystemConsoleIO(input ?: defaultConsoleInput(), output),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                [(GithubCheckClientFactory.PROVIDER): new GithubCheckClientFactory()],
                new EnvFileSecretsProvider(),
                new SystemClock(),
                new ThreadSleeper(),
                factoryProperties,
                new SandboxProperties(null, null, null, null, null, null, false))
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
     * The dominant console-input literal — twenty platform line separators,
     * enough buffered newlines for prompts the test never drives
     * interactively. Shared by both {@code newAssembly} overloads so the
     * default lives in exactly one place.
     */
    private static InputStream defaultConsoleInput() {
        new ByteArrayInputStream((System.lineSeparator() * 20).getBytes('UTF-8'))
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
}
