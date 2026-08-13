package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.SandboxProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig

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
trait AppAssemblyFixture {

    /**
     * Builds a {@link FactoryProperties} for tests, defaulting to the
     * dominant {@code new FactoryProperties('test-instance', null, null,
     * null, null)} literal seen across app-layer specs. Pass overrides by key —
     * {@code instanceName}, {@code agentCliBinary},
     * {@code agentCliEnvPassthrough}, {@code tracker} — for the sites that
     * vary one of these (fake-agent binary paths, per-instance names, env
     * passthrough lists, and — for {@code AbortLifecycleFixture}'s
     * backoff-clock scenario — a non-default {@link FactoryProperties.Tracker});
     * {@code tracker} defaults to {@code null} (= default {@code Tracker}).
     *
     * <p>Implements FR2 of refactor-app-spec-fixtures.
     */
    FactoryProperties testProperties(Map overrides = [:]) {
        new FactoryProperties(
                overrides.getOrDefault('instanceName', 'test-instance') as String,
                overrides['agentCliBinary'] as String,
                overrides['agentCliEnvPassthrough'] as List<String>,
                overrides['tracker'] as FactoryProperties.Tracker,
                overrides['check'] as FactoryProperties.Check)
    }

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
     * through a real tracker adapter.
     */
    static TrackerAdapterFactory fakeFactory(Tracker t) {
        new FixedTrackerAdapterFactory(t)
    }
}

/**
 * A {@link TrackerAdapterFactory} whose {@code create} always returns the
 * fixed {@code tracker} it was built with and whose {@code expandRef} always
 * throws. Groovy traits cannot declare an anonymous inner class directly, so
 * this is the named class {@link AppAssemblyFixture#fakeFactory} delegates
 * to.
 */
class FixedTrackerAdapterFactory implements TrackerAdapterFactory {

    private final Tracker tracker

    FixedTrackerAdapterFactory(Tracker tracker) {
        this.tracker = tracker
    }

    Tracker create(TrackerConfig config, String instanceId) {
        tracker
    }

    TaskRef expandRef(TrackerConfig config, String rawRef) {
        throw new UnsupportedOperationException('not used by this fixture')
    }
}
