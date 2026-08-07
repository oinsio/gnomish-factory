package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.serve.DaemonLifecycleState
import com.github.oinsio.gnomish.app.serve.LifecycleStateTracker
import com.github.oinsio.gnomish.app.serve.SlotLedger
import com.github.oinsio.gnomish.serveobservability.FeedPhase
import com.github.oinsio.gnomish.serveobservability.FeedSnapshot
import com.github.oinsio.gnomish.serveobservability.HeartbeatState
import com.github.oinsio.gnomish.serveobservability.HeartbeatVital
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.JanitorVital
import com.github.oinsio.gnomish.serveobservability.LifecycleSnapshotAssembler
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.serveobservability.ReaperVital
import com.github.oinsio.gnomish.serveobservability.RunSummaryAccumulator
import com.github.oinsio.gnomish.serveobservability.SlotsSnapshot
import com.github.oinsio.gnomish.serveobservability.Snapshot
import com.github.oinsio.gnomish.serveobservability.TrackerHealth
import com.github.oinsio.gnomish.serveobservability.VitalsSnapshot
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper
import com.github.oinsio.gnomish.serveobservability.json.SnapshotJsonMapper
import com.github.oinsio.gnomish.serveobservability.writer.LedgerAppender
import com.github.oinsio.gnomish.serveobservability.writer.LifecycleLedgerWriter
import com.github.oinsio.gnomish.serveobservability.writer.RotatingLedgerAppender
import com.github.oinsio.gnomish.serveobservability.writer.SnapshotWriter
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

/**
 * {@link ObservabilityWiring}: the daemon-lifetime handle {@code ServeCommand}/{@code
 * ServeShutdownWiring} drive (task 5.1). Constructed directly (package-private constructor) over
 * hand-built real collaborators, bypassing {@link ObservabilityAssembly} so this spec is scoped to
 * the wiring's OWN lifecycle-method behavior — most importantly {@link
 * ObservabilityWiring#finalizeStopped}'s idempotency, since both the drain-complete path and the
 * SIGTERM shutdown hook can reach it on the very same run (design: the JVM shutdown hook fires on
 * every exit).
 *
 * <p>Implements FR1, FR4, FR12 of add-serve-observability.
 */
// Bound every feature: a real SnapshotWriter thread on a 30s interval is started here, so a dropped
// wake/stop mutant must fail fast rather than block a test on the worker into a PIT TIMED_OUT.
@Timeout(10)
class ObservabilityWiringSpec extends Specification {

    @TempDir
    Path homeDir

    private static final String INSTANCE_NAME = 'gnomish-wiring-test'
    private static final InstanceInfo INSTANCE = new InstanceInfo('gnomish-wiring-test-ab12cd', 'worker-1', '0.1.0')

    private Path ledgerFile(Instant at) {
        ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, LocalDate.ofInstant(at, ZoneOffset.UTC))
    }

    private ObservabilityWiring newWiring(Clock clock, LifecycleStateTracker lifecycleTracker) {
        def snapshotFile = homeDir.resolve('snapshot.json')
        def snapshotWriter = new SnapshotWriter(
                snapshotFile,
                { -> fixtureSnapshot(lifecycleTracker) },
                new SnapshotJsonMapper(),
                Duration.ofSeconds(30),
                clock,
                0)
        def appender = new RotatingLedgerAppender(
                new LedgerAppender(homeDir.resolve('placeholder'), new LedgerJsonMapper()), homeDir, INSTANCE_NAME, clock)
        def lifecycleLedgerWriter = new LifecycleLedgerWriter(appender, INSTANCE, clock)
        def taskOutcomeLedgerWriter = new TaskOutcomeLedgerWriter(new SlotLedger(1), appender, INSTANCE, clock)
        snapshotWriter.start()
        return new ObservabilityWiring(lifecycleTracker, snapshotWriter, lifecycleLedgerWriter, taskOutcomeLedgerWriter, appender, INSTANCE, clock)
    }

    private static Snapshot fixtureSnapshot(LifecycleStateTracker tracker) {
        def instance = INSTANCE
        def feed = new FeedSnapshot(FeedPhase.IDLE_EMPTY, Instant.EPOCH, Instant.EPOCH, 0, 2)
        def slots = new SlotsSnapshot(2, [])
        def vitals = new VitalsSnapshot(
                new HeartbeatVital(HeartbeatState.RUNNING, Instant.EPOCH, 0),
                new ReaperVital(Instant.EPOCH, 0, 300L),
                new JanitorVital(Instant.EPOCH))
        def health = new TrackerHealth(null, 0)
        return new Snapshot(1, Instant.EPOCH, 0L, instance, LifecycleSnapshotAssembler.assemble(tracker), feed, slots, vitals, health)
    }

    def "finalizeStopped() transitions to stopped, writes the ledger line, and stops the writer — exactly once"() {
        given:
        def now = Instant.parse('2026-08-03T10:00:00Z')
        def clock = Clock.fixed(now, ZoneOffset.UTC)
        def lifecycleTracker = new LifecycleStateTracker(now)
        def wiring = newWiring(clock, lifecycleTracker)

        when: 'finalizeStopped runs once'
        wiring.finalizeStopped('sigterm')

        then: 'the lifecycle tracker moved to stopped with that reason'
        lifecycleTracker.view().state() == DaemonLifecycleState.STOPPED
        lifecycleTracker.view().reason() == 'sigterm'

        and: 'exactly one stopped ledger line was written'
        def lines = Files.readString(ledgerFile(now)).readLines().findAll { it.contains('"event":"stopped"') }
        lines.size() == 1
        lines[0].contains('"reason":"sigterm"')

        when: 'a second call arrives with a DIFFERENT reason (e.g. the JVM shutdown hook firing again on normal exit)'
        wiring.finalizeStopped('drainComplete')

        then: 'it is a no-op: the original reason and ledger line stand, no second line was appended'
        lifecycleTracker.view().reason() == 'sigterm'
        Files.readString(ledgerFile(now)).readLines().findAll { it.contains('"event":"stopped"') }.size() == 1
    }

    def "beginDraining()/beginStopping() move the lifecycle tracker through the intermediate states"() {
        given:
        def now = Instant.parse('2026-08-03T10:00:00Z')
        def clock = Clock.fixed(now, ZoneOffset.UTC)
        def lifecycleTracker = new LifecycleStateTracker(now)
        def wiring = newWiring(clock, lifecycleTracker)

        expect:
        lifecycleTracker.view().state() == DaemonLifecycleState.RUNNING

        when:
        wiring.beginDraining()

        then:
        lifecycleTracker.view().state() == DaemonLifecycleState.DRAINING

        when:
        wiring.beginStopping()

        then:
        lifecycleTracker.view().state() == DaemonLifecycleState.STOPPING
    }

    def "now() reflects the wiring's own clock"() {
        given:
        def now = Instant.parse('2026-08-03T10:00:00Z')
        def clock = Clock.fixed(now, ZoneOffset.UTC)
        def wiring = newWiring(clock, new LifecycleStateTracker(now))

        expect:
        wiring.now() == now
    }

    def "newRunSummaryLedgerWriter() returns a functional writer over the shared appender"() {
        given:
        def now = Instant.parse('2026-08-03T10:00:00Z')
        def clock = Clock.fixed(now, ZoneOffset.UTC)
        def wiring = newWiring(clock, new LifecycleStateTracker(now))
        def accumulator = new RunSummaryAccumulator()

        when:
        wiring.newRunSummaryLedgerWriter().write(accumulator, now)

        then:
        Files.readString(ledgerFile(now)).contains('"type":"runSummary"')
    }
}
