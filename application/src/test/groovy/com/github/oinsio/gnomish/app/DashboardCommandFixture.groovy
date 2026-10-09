package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.daemon.SupervisedLoopHarness
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds a {@link DashboardCommand} over a recording tracker and a spec-supplied sleeper, on a
 * fixed virtual clock, for {@code DashboardCommandSpec} and {@code DashboardCommandWatchSpec}.
 */
final class DashboardCommandFixture {

    private DashboardCommandFixture() {}

    static DashboardCommand command(Path factoryHome, Path projectDir, RecordingReadOnlyTracker tracker,
            Sleeper sleeper, String instanceName) {
        new DashboardCommand(
                VirtualTimeEquipment.on(Clock.fixed(Instant.parse('2026-08-06T00:00:00Z'), ZoneOffset.UTC), sleeper),
                RegisteredCloneFixture.scope(RegisteredCloneFixture.unregistered(factoryHome, projectDir), instanceName),
                new FactoryProperties(instanceName, null, null, null),
                new TrackerWiring([github: new RecordingTrackerAdapterFactory(tracker)], MapSecretsProvider.NONE, TrackerValidatorStub.acceptingGithubSource(), VirtualTimeEquipment.create()))
    }

    /** Kills the loop thread on every wait, the backoff's included. */
    static Sleeper killingSleeper() {
        return { Duration d ->
            throw new SupervisedLoopHarness.Unrenderable()
        } as Sleeper
    }

    /**
     * Kills the loop thread on every render-cadence wait and lets every respawn backoff return, so
     * the Bounded policy gives up after its restarts; {@code onCadenceWait} sees each killed wait.
     */
    static Sleeper givingUpSleeper(Closure onCadenceWait = {}) {
        def calls = new AtomicInteger()
        return { Duration d ->
            if (calls.incrementAndGet() % 2 == 1) {
                onCadenceWait(d)
                throw new SupervisedLoopHarness.Unrenderable()
            }
        } as Sleeper
    }
}
