package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.serve.DaemonLifecycleState
import com.github.oinsio.gnomish.app.serve.LifecycleStateTracker
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.serveobservability.writer.TaskOutcomeLedgerWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * FR1, FR11, FR12 of add-serve-observability: {@link ObservabilityWiring}'s two ENDS — the {@link
 * ObservabilityWiring#start} both {@code ServeCommand} paths call before the feed runs, and the
 * final synchronous snapshot write {@link ObservabilityWiring#finalizeStopped} forces before the
 * writer thread goes away — plus the {@link ObservabilityWiring#taskOutcomeLedgerWriter} handle
 * every slot attaches to.
 *
 * <p>Distinct from {@code ObservabilityWiringSpec}, which drives the lifecycle TRANSITIONS over an
 * already-started writer: this one constructs the wiring around a writer that has NOT been started,
 * so {@code start()}'s own two effects are observable, and it asserts the file the operator is left
 * with after a stop rather than the ledger line that accompanies it.
 *
 * <p>Added by task 8.7 of split-into-modules (design D13(c)).
 */
// Bound every feature: a real SnapshotWriter thread is started here, so a dropped start/stop
// mutant must fail fast rather than block a test on the worker into a PIT TIMED_OUT.
@Timeout(10)
class ObservabilityWiringStartStopSpec extends Specification {

    @TempDir
    Path homeDir

    private static final String INSTANCE_NAME = 'gnomish-startstop-test'
    private static final InstanceInfo INSTANCE = new InstanceInfo('gnomish-startstop-test-ab12cd', 'worker-1', '0.1.0')
    private static final Instant NOW = Instant.parse('2026-08-03T10:00:00Z')
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)

    private Path snapshotFile = null
    private TaskOutcomeLedgerWriter taskOutcomeLedgerWriter = null

    private Path ledgerFile() {
        ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, LocalDate.ofInstant(NOW, ZoneOffset.UTC))
    }

    /**
     * A wiring over a snapshot writer that has NOT been started, on an interval far longer than the
     * test: every byte the snapshot file ever receives is therefore written by an explicit
     * lifecycle call, never by a background tick that happened to land.
     */
    private ObservabilityWiring newUnstartedWiring(LifecycleStateTracker lifecycleTracker) {
        def built = ObservabilityWiringTestFixtures.build(
                homeDir, INSTANCE_NAME, INSTANCE, CLOCK, lifecycleTracker, Duration.ofHours(1), false)
        snapshotFile = built.snapshotFile
        taskOutcomeLedgerWriter = built.taskOutcomeLedgerWriter
        return built.wiring
    }

    // FR1, FR12: start() has two effects, and both matter to an operator watching a fresh daemon —
    // the snapshot file starts existing (the writer thread's immediate first write) and the ledger
    // records that this instance started.
    def "start() starts the snapshot writer and records the started ledger line"() {
        given:
        def wiring = newUnstartedWiring(new LifecycleStateTracker(NOW))

        expect: 'nothing has been written before start()'
        !Files.exists(snapshotFile)

        when:
        wiring.start()

        then: 'the writer thread performed its immediate first write'
        new PollingConditions(timeout: 5).eventually {
            assert Files.exists(snapshotFile)
        }

        and: 'the ledger carries exactly one started line for this instance'
        def started = Files.readString(ledgerFile()).readLines().findAll {
            it.contains('"event":"started"')
        }
        started.size() == 1

        cleanup:
        wiring.finalizeStopped('cleanup')
    }

    // FR4, FR12: the reason finalizeStopped ends with a FINAL SYNCHRONOUS write rather than a plain
    // stop — the file the operator is left with after the daemon exits must show `stopped`, not the
    // last state a background tick happened to catch. The writer's interval here is an hour, so the
    // only way `stopped` can reach the file is that final write.
    def "finalizeStopped() leaves the snapshot file showing stopped, not the state at the last tick"() {
        given:
        def lifecycleTracker = new LifecycleStateTracker(NOW)
        def wiring = newUnstartedWiring(lifecycleTracker)
        wiring.start()

        and: 'the file written at startup shows the daemon running'
        new PollingConditions(timeout: 5).eventually {
            assert Files.readString(snapshotFile).contains('"state" : "running"')
        }

        when:
        wiring.finalizeStopped('signal')

        then: 'the last bytes on disk reflect the stopped state this call moved the daemon into'
        lifecycleTracker.view().state() == DaemonLifecycleState.STOPPED
        Files.readString(snapshotFile).contains('"state" : "stopped"')
    }

    // FR11: every slot attaches its outcome writes to this handle, so the wiring must hand back the
    // very writer it was built over — a fresh or absent one would silently drop every task outcome.
    def "taskOutcomeLedgerWriter() hands back the writer the wiring was built over"() {
        given:
        def wiring = newUnstartedWiring(new LifecycleStateTracker(NOW))

        expect:
        wiring.taskOutcomeLedgerWriter().is(taskOutcomeLedgerWriter)
    }
}
