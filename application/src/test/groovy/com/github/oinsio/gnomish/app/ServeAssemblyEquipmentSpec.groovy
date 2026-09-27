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
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir
import spock.util.concurrent.PollingConditions

/**
 * {@link ServeAssembly}'s builders over the daemon's fixed equipment (design D7 of
 * collapse-composition-roots): each builder that reads the properties or the engine clock takes
 * them from the instance, so each scenario proves the built collaborator carries the instance's
 * own equipment.
 *
 * Implements D7, FR1 of collapse-composition-roots.
 */
class ServeAssemblyEquipmentSpec extends Specification implements RunChainFakes {

    private static final Instant NOW = Instant.parse('2026-09-27T10:00:00Z')
    private static final com.github.oinsio.gnomish.domain.engine.port.Clock ENGINE_CLOCK = {
        -> NOW
    }
    private static final ServeProperties SERVE_PROPERTIES = new ServeProperties(
    2, Duration.ofMillis(50), Duration.ofSeconds(30), Duration.ofHours(2), Duration.ofSeconds(5), 14, null, null, null)
    private static final String INSTANCE_NAME = 'gnomish-equipment-test'

    @TempDir
    Path homeDir

    private final builders = new ServeAssembly(testProperties(instanceName: INSTANCE_NAME), SERVE_PROPERTIES, ENGINE_CLOCK)

    // FR8, D12 of add-serve-observability: the decorator records health on the instance's engine clock.
    def "the tracker-health decorator stamps a success on the engine clock"() {
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

    // FR9 of add-serve-observability: the ledger lands under the given home, named after the
    // instance's own factory properties.
    def "the observability wiring writes its ledger under the home, for the configured instance"() {
        given:
        def clock = Clock.fixed(NOW, ZoneOffset.UTC)
        def slotLedger = new SlotLedger(1)
        def ref = new TaskRef('github:o/r#1')
        slotLedger.acquire()
        slotLedger.assign(ref)
        def sources = new SnapshotSources(null, slotLedger, 1, null, null, null, null, null, null, null)

        when:
        def observability = builders.observability(INSTANCE, homeDir, new ForwardingDirtyNotifier(), clock, sources)
        observability.taskOutcomeLedgerWriter().write(ref, new TakeResult.Delivered(
                        new TaskState(new Position.PipelineEnd(), 1, [], ExecutorUsage.none()), 'done'))

        then:
        def ledgerFile = ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, LocalDate.ofInstant(NOW, ZoneOffset.UTC))
        new PollingConditions(timeout: 2).eventually {
            assert Files.exists(ledgerFile)
        }

        cleanup:
        slotLedger.release(ref)
    }
}
