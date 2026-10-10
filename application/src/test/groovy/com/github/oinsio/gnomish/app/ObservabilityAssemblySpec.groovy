package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.app.lease.ClaimLostSink
import com.github.oinsio.gnomish.app.lease.HeartbeatProgress
import com.github.oinsio.gnomish.app.lease.InstanceHeartbeat
import com.github.oinsio.gnomish.app.lease.LiveClaims
import com.github.oinsio.gnomish.app.lease.ReaperDuty
import com.github.oinsio.gnomish.app.lease.StandingReaper
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerHealthTracker
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickLog
import com.github.oinsio.gnomish.app.serve.DirtyNotifier
import com.github.oinsio.gnomish.app.serve.FeedAutomaton
import com.github.oinsio.gnomish.app.serve.FeedAutomatonFixture
import com.github.oinsio.gnomish.app.serve.ForwardingDirtyNotifier
import com.github.oinsio.gnomish.app.serve.OccupiedSlots
import com.github.oinsio.gnomish.app.serve.RemoteOutageGates
import com.github.oinsio.gnomish.app.serve.SlotLedger
import com.github.oinsio.gnomish.app.serve.SlotRunner
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal
import com.github.oinsio.gnomish.app.serve.WorktreeJanitor
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * {@link ObservabilityAssembly#assemble}: task 5.1's construction-order wiring — proves the
 * returned {@link ObservabilityWiring} is genuinely functional (not a stub), that the {@link
 * ForwardingDirtyNotifier} handed in gets bound to the real writer, and that the assembled
 * snapshot content reflects the given collaborators (slot capacity, instance identity).
 *
 * <p>Implements FR1, FR4, FR7, FR9, FR12 of add-serve-observability.
 */
// Bound every feature: observability.start() spins up a real SnapshotWriter thread (30s interval in
// one scenario), so a dropped wake/stop mutant must fail fast rather than block into a PIT TIMED_OUT.
@Timeout(10)
class ObservabilityAssemblySpec extends Specification implements RunChainFakes {

    @TempDir
    Path homeDir

    /** The instance's serve directory the assembly is handed, not yet created (FR10 of add-project-registry). */
    Path serveDir() {
        homeDir.resolve('projects/widgets/serve/default')
    }

    private static final String INSTANCE_NAME = 'gnomish-observability-test'

    /**
     * The one time source of every assembled graph (FR21 of
     * supervise-daemon-loops-and-embed-dashboard): the snapshot writer and ledger, the slot ledger,
     * the tracker-health decorator, the remote-outage gate and every loop the snapshot reads all
     * read this clock, as the composition root hands them its one equipment.
     */
    private final VirtualClock clock = new VirtualClock(Instant.parse('2026-08-03T10:00:00Z'))

    /** The equipment over {@link #clock}; no component here is started, so the sleeper never runs. */
    private final TimeEquipment time = VirtualTimeEquipment.on(clock, { Duration d -> } as Sleeper)

    private FeedAutomaton newAutomaton(SlotLedger slotLedger, Tracker tracker, InstanceId instanceId, DirtyNotifier notifier) {
        FeedAutomatonFixture.feedAutomaton(
                tracker,
                instanceId,
                slotLedger,
                { TaskRef ref -> } as SlotRunner,
                time,
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                2,
                new Random(),
                notifier)
    }

    private InstanceHeartbeat newHeartbeat(Tracker tracker) {
        new InstanceHeartbeat(
                tracker,
                new HeartbeatProgress(),
                time,
                Duration.ofSeconds(30),
                ClaimLostSink.IGNORE)
    }

    private StandingReaper newStandingReaper() {
        new StandingReaper(
                ReaperDuty.NONE,
                Duration.ofSeconds(30), {
                    []
                } as LiveClaims, time)
    }

    private WorktreeJanitor newWorktreeJanitor() {
        new WorktreeJanitor(
                RegisteredCloneFixture.unregistered(homeDir, homeDir.resolve('clone')),
                Duration.ofDays(1),
                { String key -> } as TaskEnvironmentDisposal,
                time,
                { -> Set.of() } as OccupiedSlots)
    }

    def "assembles a functional ObservabilityWiring: binds the dirty notifier and writes a snapshot reflecting the given collaborators"() {
        given:
        def instanceId = InstanceId.generate(INSTANCE_NAME)
        def tracker = Stub(Tracker)
        def trackerHealth = new TrackerHealthTracker(tracker, clock)
        def dirtyNotifier = new ForwardingDirtyNotifier()
        def slotLedger = new SlotLedger(3, clock, dirtyNotifier)
        def automaton = newAutomaton(slotLedger, tracker, instanceId, dirtyNotifier)
        def serveProperties = new ServeProperties(0, null, null, null, Duration.ofMillis(20), 0, null, null, null, null)

        when:
        def observability = ObservabilityAssembly.assemble(
                serveProperties,
                instanceId,
                serveDir(),
                dirtyNotifier,
                time,
                new SnapshotSources(
                        automaton,
                        slotLedger,
                        3,
                        new HeartbeatProgress(),
                        trackerHealth,
                        newHeartbeat(tracker),
                        newStandingReaper(),
                        newWorktreeJanitor(),
                        new SweepTickLog(Duration.ofDays(7), clock, 20),
                        RemoteOutageGates.forServe(BaseRefGit.UNWIRED, homeDir, new ServeProperties(0, null, null, null, null, null, null, null, null, null), clock, {}, { ignored -> })))

        then: 'a genuine, non-null wiring is returned'
        observability != null

        and: 'the dirty notifier is now bound to the real writer, not the NOOP default'
        dirtyNotifier.isBound()

        when: 'started, beside the worktree janitor in production'
        observability.start()

        then: 'the snapshot file materializes at the deterministic path, reflecting the given identity/capacity'
        def snapshotFile = ObservabilityPaths.snapshotFile(serveDir())
        new PollingConditions(timeout: 2).eventually {
            assert Files.exists(snapshotFile)
        }
        def json = Files.readString(snapshotFile)
        json.contains(instanceId.value())
        json.contains('"capacity" : 3')

        and: 'the instance host resolves to the real local hostname, not an empty placeholder ' +
        '(kills resolveHost\'s replaced-return-value mutant)'
        json.contains('"host" : "' + InetAddress.getLocalHost().getHostName() + '"')

        and: 'FR6 of add-release-pipeline: the factory version is FactoryVersion\'s — the development ' +
        'version, since no build info is on the test classpath'
        json.contains('"factoryVersion" : "0.0.0-dev"')

        and: 'the started ledger line landed too'
        def ledgerFile = ObservabilityPaths.ledgerFile(serveDir(), LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
        Files.readString(ledgerFile).contains('"event":"started"')
    }

    def "the taskOutcomeLedgerWriter is wired over the SAME slotLedger passed in"() {
        given:
        def instanceId = InstanceId.generate(INSTANCE_NAME)
        def tracker = Stub(Tracker)
        def trackerHealth = new TrackerHealthTracker(tracker, clock)
        def dirtyNotifier = new ForwardingDirtyNotifier()
        def slotLedger = new SlotLedger(1, clock, dirtyNotifier)
        def ref = new TaskRef('github:o/r#1')
        slotLedger.acquire()
        slotLedger.assign(ref)
        def automaton = newAutomaton(slotLedger, tracker, instanceId, dirtyNotifier)
        def serveProperties = new ServeProperties(0, null, null, null, Duration.ofSeconds(30), 0, null, null, null, null)

        when:
        def observability = ObservabilityAssembly.assemble(
                serveProperties,
                instanceId,
                serveDir(),
                dirtyNotifier,
                time,
                new SnapshotSources(
                        automaton,
                        slotLedger,
                        1,
                        new HeartbeatProgress(),
                        trackerHealth,
                        newHeartbeat(tracker),
                        newStandingReaper(),
                        newWorktreeJanitor(),
                        new SweepTickLog(Duration.ofDays(7), clock, 20),
                        RemoteOutageGates.forServe(BaseRefGit.UNWIRED, homeDir, new ServeProperties(0, null, null, null, null, null, null, null, null, null), clock, {}, { ignored -> })))
        def finalState = new TaskState(new Position.PipelineEnd(), 1, [], ExecutorUsage.none())
        observability.taskOutcomeLedgerWriter().write(ref, new TakeResult.Delivered(finalState, 'done'))

        then: 'the remoteOutage write point exists too (NFR-O1, NFR-O3 of add-base-ref-resolution)'
        observability.remoteOutageLedgerWriter() != null

        then: 'a taskOutcome line lands for the SAME ref this test assigned to the slot ledger'
        def ledgerFile = ObservabilityPaths.ledgerFile(serveDir(), LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
        new PollingConditions(timeout: 2).eventually {
            assert Files.exists(ledgerFile)
        }
        Files.readString(ledgerFile).contains('"taskId":"github:o/r#1"')

        cleanup:
        slotLedger.release(ref)
    }
}
