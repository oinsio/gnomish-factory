package com.github.oinsio.gnomish.dashboard

import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Task 7.5 of add-serve-sandbox-lifecycle (M5): the sandbox hygiene section renders all four
 * breakdown groups from a REAL snapshot file (the {@code snapshot-v1.reference.json} fixture,
 * whose {@code vitals.sweep.counts} already exercises every one of the six verdict categories)
 * plus a real ledger fixture — driven through the full {@link DashboardRenderCycle}, disk to
 * HTML, not through hand-built view objects.
 *
 * <p>redesign-dashboard re-homed what this spec checks without changing what the data means: the
 * four groups render as the hygiene block's quiet rows, the per-object depth (kept inventory,
 * actions table) is gone from the page, and the ledger-derived dead-instance incident surfaces as
 * an alarm line in the status card.
 *
 * <p>Implements M5 of add-serve-sandbox-lifecycle.
 */
class DashboardSandboxHygieneFixtureSpec extends Specification {

    @TempDir
    Path homeDir

    private static final String INSTANCE_NAME = 'fixture-instance'
    private static final Instant NOW = Instant.parse('2026-08-06T09:00:00Z')

    def renderCycle = new DashboardRenderCycle()

    private static String readClasspathResource(String name) {
        DashboardSandboxHygieneFixtureSpec.classLoader.getResourceAsStream(name).withCloseable {
            new String(it.readAllBytes(), StandardCharsets.UTF_8)
        }
    }

    // M5: the four breakdown groups (cleaned, stopped, checked-and-untouched, skipped-without-
    //     verdict — mapped over all six verdict categories) plus the kept inventory and recent
    //     ledger actions, all read from real files on disk in one render pass.
    def "the hygiene section renders all four breakdown groups from a real snapshot, plus recent actions from a real ledger"() {
        given: 'the shared snapshot-v1 reference fixture, whose sweep counts already cover every category'
        def snapshotFile = ObservabilityPaths.snapshotFile(homeDir, INSTANCE_NAME)
        Files.createDirectories(snapshotFile.parent)
        Files.writeString(snapshotFile, readClasspathResource('snapshot-v1.reference.json'), StandardCharsets.UTF_8)

        and: 'a real ledger fixture carrying stop and dispose sweep-action lines plus the tick summary'
        def ledgerFile = ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, NOW.atZone(ZoneOffset.UTC).toLocalDate())
        Files.createDirectories(ledgerFile.parent)
        Files.writeString(
                ledgerFile,
                '{"version":1,"type":"sweepAction","at":"2026-08-06T08:58:30Z","objectName":"gnomish-task-40-box",' +
                '"role":"main-box","mode":"tracked","taskKey":"task-40","category":"stoppedOrphan",' +
                '"reason":"unowned running main-box","ageSeconds":900}\n' +
                '{"version":1,"type":"sweepAction","at":"2026-08-06T08:58:31Z","objectName":"gnomish-task-41-vol",' +
                '"role":"remnant","mode":"tracked","taskKey":"task-41","category":"disposedAged",' +
                '"reason":"kept past reap threshold","ageSeconds":518400}\n' +
                '{"version":1,"type":"sweepTick","at":"2026-08-06T08:58:00Z","checkedAlive":4,' +
                '"keptUnderThreshold":2,"stoppedOrphan":1,"disposedAged":1,"disposedReconstructible":3,' +
                '"skippedNoVerdict":0}\n',
                StandardCharsets.UTF_8)

        when:
        def html = renderCycle.render(homeDir, INSTANCE_NAME, new BoardSectionView(null, null, null), NOW, null)

        then: 'cleaned = disposedAged(1) + disposedReconstructible(3) = 4, stopped = 1,'
        // 'checked and untouched = checkedAlive(4) + keptUnderThreshold(2) = 6, skipped = 0'
        html.contains('<span class="row__label">cleaned</span><span class="row__count num" title="4">4</span>')
        html.contains('<span class="row__label">stopped</span><span class="row__count num" title="1">1</span>')
        html.contains(
                '<span class="row__label">checked and untouched</span><span class="row__count num" title="6">6</span>')
        html.contains(
                '<span class="row__label">skipped without verdict</span><span class="row__count num" title="0">0</span>')

        and: 'the per-object depth the redesign dropped is absent: no kept inventory, no actions table'
        !html.contains('time to reap')
        !html.contains('<table')
        !html.contains('<td>')

        and: 'a tracked stopped-orphan action still raises the dead-instance incident, now in the status card (UX2)'
        html.contains('<div class="status__alert">an instance died or hung')
        html.contains('task-40')

        and: 'the hygiene block itself carries no alert styling at all'
        !html.contains('sandbox-alert')
    }
}
