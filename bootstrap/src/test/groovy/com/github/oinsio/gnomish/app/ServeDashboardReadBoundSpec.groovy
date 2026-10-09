package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.tracker.inmemory.InMemoryTracker
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.OpenTask
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The embedded dashboard's tracker cost (NFR-P1, design D11 of
 * supervise-daemon-loops-and-embed-dashboard): a {@code gnomish serve --dashboard} assembled
 * through the production owners runs its page for one virtual hour at the default cadences, and
 * the page's own client — the separate reader {@link BoardReaders#boardReader} builds, told apart
 * by {@link BoardReaderSplitFactory} — sees one {@code listReady} and one {@code listOpen} per
 * board interval and no more, however often the page re-renders in between.
 *
 * <p>Only the dashboard moves the virtual clock ({@link DashboardOnlySleeper}); every other loop
 * ticks once and parks, so the daemon's own tracker reads cannot be mistaken for the page's.
 *
 * <p>Implements FR10, NFR-P1 of supervise-daemon-loops-and-embed-dashboard.
 */
class ServeDashboardReadBoundSpec extends ServeCommandSpecBase {

    private static final Instant START = Instant.parse('2026-10-09T00:00:00Z')
    private static final Duration RUN = Duration.ofHours(1)

    // FR10, NFR-P1 (factory-serve "Tracker reads bounded").
    def "NFR-P1: an hour of serve --dashboard costs the tracker one listReady and one listOpen per board interval"() {
        given: "a board reader that records the virtual instant of each read"
        def clock = new VirtualClock(START)
        List<Instant> readyReads = Collections.synchronizedList([])
        List<Instant> openReads = Collections.synchronizedList([])
        def boardReader = new InMemoryTracker() {
                    @Override
                    List<ReadyTask> listReady(int limit) {
                        readyReads << clock.instant()
                        []
                    }

                    @Override
                    List<OpenTask> listOpen() {
                        openReads << clock.instant()
                        []
                    }
                }

        and: 'a daemon whose page alone runs on virtual time, for one hour'
        writeConfig(GITHUB_TRACKER_SECTION)
        def sleeper = new DashboardOnlySleeper(clock, START.plus(RUN))
        def properties = testProperties(instanceName: INSTANCE_NAME)
        def command = ServeCommands.of(
                newAssembly(properties, VirtualTimeEquipment.on(clock, sleeper)), TaskGitFixture.real(),
                registeredClone, 'taskId', properties,
                new ServeProperties(0, null, null, null, null, null, null, null, null, null),
                new TrackerWiring([github: new BoardReaderSplitFactory(tracker, boardReader)], MapSecretsProvider.NONE,
                TrackerValidatorStub.acceptingGithubSource(), VirtualTimeEquipment.create()),
                new CapturingStarter(), SandboxLifecyclePass.NONE, ContainerTakeSupport.hostOnly(),
                new ScriptedConsoleIO())

        when:
        runsToCompletion {
            command.run(args('serve', "--dir=$projectDir", '--dashboard'))
        }
        assert sleeper.horizonReached.await(30, TimeUnit.SECONDS)

        then: 'one read of each kind per board interval across the hour, never two inside one interval'
        int intervals = (int) (RUN.seconds / DashboardWatch.BOARD_CADENCE.seconds)
        readyReads.size() == intervals + 1
        openReads.size() == intervals + 1
        [readyReads, openReads].every { reads ->
            (1..<reads.size()).every {
                Duration.between(reads[it - 1], reads[it]) >= DashboardWatch.BOARD_CADENCE
            }
        }
    }
}
