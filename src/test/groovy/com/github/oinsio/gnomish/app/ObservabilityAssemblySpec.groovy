package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.app.lease.ClaimLostSink
import com.github.oinsio.gnomish.app.lease.HeartbeatProgress
import com.github.oinsio.gnomish.app.lease.InstanceHeartbeat
import com.github.oinsio.gnomish.app.lease.ReaperDuty
import com.github.oinsio.gnomish.app.lease.StandingReaper
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerHealthTracker
import com.github.oinsio.gnomish.app.serve.DirtyNotifier
import com.github.oinsio.gnomish.app.serve.FeedAutomaton
import com.github.oinsio.gnomish.app.serve.ForwardingDirtyNotifier
import com.github.oinsio.gnomish.app.serve.SlotLedger
import com.github.oinsio.gnomish.app.serve.SlotRunner
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal
import com.github.oinsio.gnomish.app.serve.WorktreeJanitor
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir
import spock.util.concurrent.PollingConditions

/**
 * {@link ObservabilityAssembly#assemble}: task 5.1's construction-order wiring — proves the
 * returned {@link ObservabilityWiring} is genuinely functional (not a stub), that the {@link
 * ForwardingDirtyNotifier} handed in gets bound to the real writer, and that the assembled
 * snapshot content reflects the given collaborators (slot capacity, instance identity).
 *
 * <p>Implements FR1, FR4, FR7, FR9, FR12 of add-serve-observability.
 */
class ObservabilityAssemblySpec extends Specification implements AppAssemblyFixture {

    @TempDir
    Path homeDir

    private static final String INSTANCE_NAME = 'gnomish-observability-test'

    private static FeedAutomaton newAutomaton(SlotLedger slotLedger, Tracker tracker, InstanceId instanceId, DirtyNotifier notifier) {
        new FeedAutomaton(
                tracker,
                instanceId,
                slotLedger,
                { TaskRef ref -> } as SlotRunner,
                { Duration d -> } as com.github.oinsio.gnomish.domain.engine.port.Sleeper,
                { -> Instant.now() } as com.github.oinsio.gnomish.domain.engine.port.Clock,
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                2,
                new Random(),
                notifier)
    }

    private InstanceHeartbeat newHeartbeat(Tracker tracker, com.github.oinsio.gnomish.domain.engine.port.Clock clock) {
        new InstanceHeartbeat(
                tracker,
                new HeartbeatProgress(),
                { Duration d -> } as com.github.oinsio.gnomish.domain.engine.port.Sleeper,
                clock,
                Duration.ofSeconds(30),
                ClaimLostSink.IGNORE)
    }

    private static StandingReaper newStandingReaper(com.github.oinsio.gnomish.domain.engine.port.Clock clock) {
        new StandingReaper(
                ReaperDuty.NONE,
                { Duration d -> } as com.github.oinsio.gnomish.domain.engine.port.Sleeper,
                Duration.ofSeconds(30),
                { [] } as java.util.function.Supplier,
                clock)
    }

    private WorktreeJanitor newWorktreeJanitor(com.github.oinsio.gnomish.domain.engine.port.Clock clock) {
        new WorktreeJanitor(
                homeDir.resolve('worktrees'),
                homeDir.resolve('clone'),
                Duration.ofDays(1),
                { String key -> } as TaskEnvironmentDisposal,
                clock,
                { Duration d -> } as com.github.oinsio.gnomish.domain.engine.port.Sleeper,
                { -> Set.of() } as java.util.function.Supplier)
    }

    def "assembles a functional ObservabilityWiring: binds the dirty notifier and writes a snapshot reflecting the given collaborators"() {
        given:
        def instanceId = InstanceId.generate(INSTANCE_NAME)
        def tracker = Stub(Tracker)
        def trackerHealth = new TrackerHealthTracker(tracker, { -> Instant.now() } as com.github.oinsio.gnomish.domain.engine.port.Clock)
        def dirtyNotifier = new ForwardingDirtyNotifier()
        def clock = Clock.fixed(Instant.parse('2026-08-03T10:00:00Z'), ZoneOffset.UTC)
        def slotLedger = new SlotLedger(3, { -> clock.instant() } as com.github.oinsio.gnomish.domain.engine.port.Clock, dirtyNotifier)
        def automaton = newAutomaton(slotLedger, tracker, instanceId, dirtyNotifier)
        def serveProperties = new ServeProperties(0, null, null, null, Duration.ofMillis(20), 0)
        def engineClock = { -> clock.instant() } as com.github.oinsio.gnomish.domain.engine.port.Clock

        when:
        def observability = ObservabilityAssembly.assemble(
                testProperties(instanceName: INSTANCE_NAME),
                serveProperties,
                instanceId,
                homeDir,
                dirtyNotifier,
                slotLedger,
                3,
                automaton,
                trackerHealth,
                new HeartbeatProgress(),
                newHeartbeat(tracker, engineClock),
                newStandingReaper(engineClock),
                newWorktreeJanitor(engineClock),
                clock)

        then: 'a genuine, non-null wiring is returned'
        observability != null

        and: 'the dirty notifier is now bound to the real writer, not the NOOP default'
        dirtyNotifier.@delegate != DirtyNotifier.NOOP

        when: 'started, beside the worktree janitor in production'
        observability.start()

        then: 'the snapshot file materializes at the deterministic path, reflecting the given identity/capacity'
        def snapshotFile = ObservabilityPaths.snapshotFile(homeDir, INSTANCE_NAME)
        new PollingConditions(timeout: 2).eventually {
            assert Files.exists(snapshotFile)
        }
        def json = Files.readString(snapshotFile)
        json.contains(instanceId.value())
        json.contains('"capacity" : 3')

        and: 'the instance host resolves to the real local hostname, not an empty placeholder ' +
        '(kills resolveHost\'s replaced-return-value mutant)'
        json.contains('"host" : "' + InetAddress.getLocalHost().getHostName() + '"')

        and: 'the factory version falls back to "dev" when no Implementation-Version manifest ' +
        'attribute is present (as under the test classpath) — kills resolveFactoryVersion\'s ' +
        'negated-conditional and replaced-return-value mutants'
        json.contains('"factoryVersion" : "dev"')

        and: 'the started ledger line landed too'
        def ledgerFile = ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, java.time.LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
        Files.readString(ledgerFile).contains('"event":"started"')
    }

    def "the taskOutcomeLedgerWriter is wired over the SAME slotLedger passed in"() {
        given:
        def instanceId = InstanceId.generate(INSTANCE_NAME)
        def tracker = Stub(Tracker)
        def trackerHealth = new TrackerHealthTracker(tracker, { -> Instant.now() } as com.github.oinsio.gnomish.domain.engine.port.Clock)
        def dirtyNotifier = new ForwardingDirtyNotifier()
        def clock = Clock.fixed(Instant.parse('2026-08-03T10:00:00Z'), ZoneOffset.UTC)
        def slotLedger = new SlotLedger(1, { -> clock.instant() } as com.github.oinsio.gnomish.domain.engine.port.Clock, dirtyNotifier)
        def ref = new TaskRef('github:o/r#1')
        slotLedger.acquire()
        slotLedger.assign(ref)
        def automaton = newAutomaton(slotLedger, tracker, instanceId, dirtyNotifier)
        def serveProperties = new ServeProperties(0, null, null, null, Duration.ofSeconds(30), 0)
        def engineClock = { -> clock.instant() } as com.github.oinsio.gnomish.domain.engine.port.Clock

        when:
        def observability = ObservabilityAssembly.assemble(
                testProperties(instanceName: INSTANCE_NAME),
                serveProperties,
                instanceId,
                homeDir,
                dirtyNotifier,
                slotLedger,
                1,
                automaton,
                trackerHealth,
                new HeartbeatProgress(),
                newHeartbeat(tracker, engineClock),
                newStandingReaper(engineClock),
                newWorktreeJanitor(engineClock),
                clock)
        def finalState = new TaskState(new Position.PipelineEnd(), 1, [], ExecutorUsage.none())
        observability.taskOutcomeLedgerWriter().write(ref, new TakeResult.Delivered(finalState, 'done'))

        then: 'a taskOutcome line lands for the SAME ref this test assigned to the slot ledger'
        def ledgerFile = ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, java.time.LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
        new PollingConditions(timeout: 2).eventually {
            assert Files.exists(ledgerFile)
        }
        Files.readString(ledgerFile).contains('"taskId":"github:o/r#1"')

        cleanup:
        slotLedger.release(ref)
    }
}
