package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.serve.ForwardingDirtyNotifier
import com.github.oinsio.gnomish.app.serve.SlotLedger
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.InterruptOnlySleeper
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link ServeAssembly}'s builders over the daemon's fixed equipment (design D7 of
 * collapse-composition-roots): each builder that reads the properties or the clock takes
 * them from the instance, so each scenario proves the built collaborator carries the instance's
 * own equipment.
 *
 * Implements D7, FR1 of collapse-composition-roots. FR10 of add-project-registry: the observability
 * builder is where the registered clone and the configured instance name meet.
 */
class ServeAssemblyEquipmentSpec extends Specification implements RunChainFakes {

    private static final Instant NOW = Instant.parse('2026-09-27T10:00:00Z')
    /** The one time source every builder and every collaborator of a scenario reads (FR21). */
    private final VirtualClock clock = new VirtualClock(NOW)
    private static final ServeProperties SERVE_PROPERTIES = new ServeProperties(
    2, Duration.ofMillis(50), Duration.ofSeconds(30), Duration.ofHours(2), Duration.ofSeconds(5), 14, null, null, null, null)
    private static final String INSTANCE_NAME = 'gnomish-equipment-test'

    @TempDir
    Path homeDir

    private ServeAssembly builders

    def setup() {
        builders = assemblyFor('widgets', INSTANCE_NAME)
    }

    private ServeAssembly assemblyFor(String project, String instanceName) {
        def clone = RegisteredCloneFixture.unregistered(homeDir, homeDir.resolve("clones/${project}"), project)
        new ServeAssembly(testProperties(instanceName: instanceName), SERVE_PROPERTIES, VirtualTimeEquipment.on(clock, new InterruptOnlySleeper()),
        RegisteredCloneFixture.provider(clone))
    }

    private static SnapshotSources sourcesOver(SlotLedger slotLedger) {
        new SnapshotSources(null, slotLedger, 1, null, null, null, null, null, null, null)
    }

    // FR8, D12 of add-serve-observability: the decorator records health on the instance's clock.
    def "the tracker-health decorator stamps a success on the assembly's clock"() {
        given:
        def tracker = Mock(Tracker)

        when:
        def health = builders.trackerHealth(tracker)
        health.listReady(1)

        then:
        1 * tracker.listReady(1) >> []
        health.lastSuccessAt() == NOW
    }

    def "the slot ledger holds the effective slot count"() {
        expect:
        builders.slotLedger(3, new ForwardingDirtyNotifier()).totalSlots() == 3
    }

    // FR14 of add-base-ref-resolution: a fresh daemon's gate starts closed.
    def "the remote-outage gate starts closed"() {
        when:
        def gate = builders.remoteOutageGate(BaseRefGit.UNWIRED, CLONE_DIR, { -> }, { outage -> })

        then:
        !gate.health().open()
    }

    // FR9 of add-serve-observability; FR10 of add-project-registry: the ledger lands in the
    // registered project's serve directory, named after the instance's own factory properties.
    def "the observability wiring writes its ledger in the project's serve directory, for the configured instance"() {
        given:
        def slotLedger = new SlotLedger(1, clock)
        def ref = new TaskRef('github:o/r#1')
        slotLedger.acquire()
        slotLedger.assign(ref)
        def sources = sourcesOver(slotLedger)

        when:
        def observability = builders.observability(INSTANCE, new ForwardingDirtyNotifier(), sources)
        observability.taskOutcomeLedgerWriter().write(ref, new TakeResult.Delivered(
                        new TaskState(new Position.PipelineEnd(), 1, [], ExecutorUsage.none()), 'done'))

        then:
        def ledgerFile = ObservabilityPaths.ledgerFile(
                homeDir.resolve("projects/widgets/serve/${INSTANCE_NAME}"), LocalDate.ofInstant(NOW, ZoneOffset.UTC))
        // The append is synchronous (LedgerAppender writes, flushes and closes before returning).
        Files.exists(ledgerFile)

        cleanup:
        slotLedger.release(ref)
    }

    def "FR10 of add-project-registry: two projects with the default instance name write to separate serve directories"() {
        given: 'daemons for projects widgets and gateway, both with the default instance name'
        def ref = new TaskRef('github:o/r#1')
        def delivered = new TakeResult.Delivered(new TaskState(new Position.PipelineEnd(), 1, [], ExecutorUsage.none()), 'done')
        def widgetsLedger = new SlotLedger(1, clock)
        def gatewayLedger = new SlotLedger(1, clock)
        [widgetsLedger, gatewayLedger].each { it.acquire(); it.assign(ref) }
        def widgets = assemblyFor('widgets', null)
                .observability(INSTANCE, new ForwardingDirtyNotifier(), sourcesOver(widgetsLedger))
        def gateway = assemblyFor('gateway', null)
                .observability(INSTANCE, new ForwardingDirtyNotifier(), sourcesOver(gatewayLedger))

        when: 'each records one outcome'
        widgets.taskOutcomeLedgerWriter().write(ref, delivered)
        gateway.taskOutcomeLedgerWriter().write(ref, delivered)

        then: 'each ledger holds exactly its own line, under its own project'
        def today = LocalDate.ofInstant(NOW, ZoneOffset.UTC)
        Files.readAllLines(ObservabilityPaths.ledgerFile(homeDir.resolve('projects/widgets/serve/default'), today)).size() == 1
        Files.readAllLines(ObservabilityPaths.ledgerFile(homeDir.resolve('projects/gateway/serve/default'), today)).size() == 1

        cleanup:
        [widgetsLedger, gatewayLedger].each { it.release(ref) }
    }
}
