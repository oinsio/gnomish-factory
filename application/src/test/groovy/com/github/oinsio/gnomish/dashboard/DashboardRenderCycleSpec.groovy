package com.github.oinsio.gnomish.dashboard

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Verifies {@link DashboardRenderCycle} composes one full render (task
 * 4.1-4.4): the daemon and history sections are re-read fresh from disk on
 * every call (FR9), and a malformed (non-tail) ledger line degrades the
 * history section to empty rather than failing the whole render (FR3,
 * design D9) — the same "a degraded section never fails the others"
 * contract {@link SnapshotReader} already gives the daemon section.
 *
 * FR3, FR6, FR9, NFR-O1, NFR-R1 of add-dashboard-page; NFR-P1 of redesign-dashboard — the
 * redesign is presentation-only, so the cycle still reads the same three on-disk sources and
 * adds none.
 */
class DashboardRenderCycleSpec extends Specification {


    @TempDir
    Path homeDir

    private static final String INSTANCE_NAME = 'render-cycle-instance'
    private static final Instant NOW = Instant.parse('2026-08-06T09:00:00Z')

    def renderCycle = new DashboardRenderCycle()

    def "a malformed non-tail ledger line degrades the history section to empty rather than failing the render"() {
        given: 'a non-last line that is not valid JSON at all -- a genuine error per the reader contract'
        def file = ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, NOW.atZone(ZoneOffset.UTC).toLocalDate())
        Files.createDirectories(file.parent)
        Files.writeString(file, 'not json at all\n{"version":1,"type":"taskOutcome","outcome":"delivered","tokensByModel":{}}\n', StandardCharsets.UTF_8)

        and: 'FR5 of harden-logging-observability: an empty block and a healthy-quiet one render alike'
        def logs = LogCaptureSupport.attach(DashboardRenderCycle)

        when:
        def html = renderCycle.render(homeDir, INSTANCE_NAME, new BoardSectionView(null, null, null), NOW, null)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        html.contains('No finished tasks yet')
        !html.toLowerCase().contains('exception')

        and: 'NFR-O3 of add-serve-sandbox-lifecycle: the hygiene block degrades on the same file'
        html.contains('Sandbox sweep has not run yet')

        and: 'both degraded blocks say so, each with the failure that caused it'
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 2
        warnings*.formattedMessage.any {
            it.startsWith(OperatorEvent.SWEEP_ACTION_LEDGER_UNAGGREGATABLE.head())
        }
        warnings*.formattedMessage.any {
            it.startsWith(OperatorEvent.OUTCOME_LEDGER_UNAGGREGATABLE.head())
        }
        warnings.every { it.throwableProxy != null }
    }

    // NFR-O3 of add-serve-sandbox-lifecycle: the hygiene section reads the ledger's sweep actions
    //     even when no snapshot exists at all, and the daemon section still degrades on its own.
    def "sweep actions from the ledger reach the hygiene section without any snapshot"() {
        given:
        def file = ObservabilityPaths.ledgerFile(
                homeDir, INSTANCE_NAME, NOW.atZone(ZoneOffset.UTC).toLocalDate())
        Files.createDirectories(file.parent)
        Files.writeString(
                file,
                '{"version":1,"type":"sweepAction","at":"2026-08-06T08:00:00Z","objectName":"zombie-box",' +
                '"role":"main-box","mode":"tracked","taskKey":"task-9","category":"stoppedOrphan",' +
                '"reason":"unowned running main-box","ageSeconds":900}\n',
                StandardCharsets.UTF_8)

        when:
        def html = renderCycle.render(homeDir, INSTANCE_NAME, new BoardSectionView(null, null, null), NOW, null)

        then: 'the action raises the dead-instance incident, now as an alarm line in the status card (UX2)'
        html.contains('an instance died or hung: stopped zombie-box of task task-9')
        html.contains('<div class="status__alert">an instance died or hung')

        and: 'the hygiene block itself stays the quiet footnote: no snapshot vital means no tick to show'
        html.contains('Sandbox sweep has not run yet')

        and: 'no per-object action row reaches the page — that depth stays with the ledger'
        !html.contains('class="row"')

        and: 'the status card still degrades on its own'
        html.contains('Daemon has not run here')
    }
}
