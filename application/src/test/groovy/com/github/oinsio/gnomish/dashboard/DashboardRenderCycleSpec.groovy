package com.github.oinsio.gnomish.dashboard

import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
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
 * FR3, FR6, FR9, NFR-O1, NFR-R1 of add-dashboard-page.
 */
class DashboardRenderCycleSpec extends Specification {

    @TempDir
    Path homeDir

    private static final String INSTANCE_NAME = 'render-cycle-instance'
    private static final Instant NOW = Instant.parse('2026-08-06T09:00:00Z')

    def renderCycle = new DashboardRenderCycle()

    def "a malformed non-tail ledger line degrades the history section to empty rather than failing the render"() {
        given: 'a non-last line that is not valid JSON at all -- a genuine error per the reader contract'
        def file = ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, NOW.atZone(java.time.ZoneOffset.UTC).toLocalDate())
        Files.createDirectories(file.parent)
        Files.writeString(file, 'not json at all\n{"version":1,"type":"taskOutcome","outcome":"delivered","tokensByModel":{}}\n', StandardCharsets.UTF_8)

        when:
        def html = renderCycle.render(homeDir, INSTANCE_NAME, new BoardSectionView(null, null, null), NOW, null)

        then:
        html.contains('no history data')
        !html.toLowerCase().contains('exception')
    }
}
