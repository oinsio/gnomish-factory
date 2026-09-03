package com.github.oinsio.gnomish.dashboard

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link SweepActionAggregator}, task 6.3 of add-serve-sandbox-lifecycle (NFR-O3, UX1): reads the
 * ledger's {@code sweepAction} lines for the recent-actions table, newest first and bounded, with
 * the same degradation {@link LedgerAggregator} gives the history section — a missing day, a torn
 * tail, and a line from a newer factory version must all leave the window standing.
 */
class SweepActionAggregatorSpec extends Specification {

    static final String INSTANCE = 'gnome-1'
    static final LocalDate TODAY = LocalDate.parse('2026-08-06')

    @TempDir
    Path tempDir

    def aggregator = new SweepActionAggregator()

    // NFR-O3: every field of the line reaches the row.
    def "reads each sweepAction line field-for-field"() {
        given:
        writeLedgerFile(TODAY, [
            actionLine('stoppedOrphan', 'box-1', 'tracked', 'task-1', 900)
        ])

        when:
        def window = aggregator.aggregate(tempDir, INSTANCE, TODAY, 1)

        then:
        window.total() == 1
        window.rows() == [
            new SweepActionRow(
            Instant.parse('2026-08-06T09:00:00Z'), 'box-1', 'main-box', 'tracked', 'task-1',
            SweepVerdictCategory.STOPPED_ORPHAN, 'unowned running', 900L)
        ]
    }

    def "reads each acting category"() {
        given:
        writeLedgerFile(TODAY, [
            actionLine(wire, 'box-1', 'tracked', 'task-1', 1)
        ])

        expect:
        aggregator.aggregate(tempDir, INSTANCE, TODAY, 1).rows()[0].category() == category

        where:
        wire | category
        'stoppedOrphan' | SweepVerdictCategory.STOPPED_ORPHAN
        'disposedAged' | SweepVerdictCategory.DISPOSED_AGED
        'disposedReconstructible' | SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE
    }

    // UX1: the operator reads the newest incident first, across days.
    def "rows come back newest first, across the whole window"() {
        given:
        writeLedgerFile(TODAY.minusDays(1), [
            actionLine('disposedAged', 'old-box', 'manual', 'task-0', 5)
        ])
        writeLedgerFile(TODAY, [
            actionLine('stoppedOrphan', 'mid-box', 'tracked', 'task-1', 5),
            actionLine('stoppedOrphan', 'new-box', 'tracked', 'task-2', 5)
        ])

        when:
        def window = aggregator.aggregate(tempDir, INSTANCE, TODAY, 2)

        then:
        window.rows()*.objectName() == [
            'new-box',
            'mid-box',
            'old-box'
        ]
    }

    // NFR-O3: the table is bounded, and the pre-truncation total is reported so the section can
    //     state what it dropped instead of implying a quiet window.
    def "the table stops at the bound while the total states the truth"() {
        given:
        writeLedgerFile(TODAY, (1..(SweepActionAggregator.MAX_ACTIONS + 5)).collect {
            actionLine('stoppedOrphan', "box-${it}", 'tracked', 'task-1', 5)
        })

        when:
        def window = aggregator.aggregate(tempDir, INSTANCE, TODAY, 1)

        then:
        window.rows().size() == SweepActionAggregator.MAX_ACTIONS
        window.total() == SweepActionAggregator.MAX_ACTIONS + 5

        and: 'the newest survive the bound, not the oldest'
        window.rows()[0].objectName() == "box-${SweepActionAggregator.MAX_ACTIONS + 5}"
    }

    // NFR-O2: untouched objects are never itemized, and other line kinds belong to other sections.
    def "non-sweepAction lines are ignored"() {
        given:
        writeLedgerFile(TODAY, [
            '{"version":1,"type":"lifecycle","event":"started"}',
            '{"version":1,"type":"sweepTick","at":"2026-08-06T09:00:00Z","counts":{}}',
            actionLine('stoppedOrphan', 'box-1', 'tracked', 'task-1', 5)
        ])

        when:
        def window = aggregator.aggregate(tempDir, INSTANCE, TODAY, 1)

        then:
        window.rows()*.objectName() == ['box-1']
    }

    // NFR-O3: a line from a newer factory version must never crash the render.
    def "a line with an unknown, missing, or unparseable field is skipped, not a crash"() {
        given:
        writeLedgerFile(TODAY, [
            '{"version":1,"type":"sweepAction","at":"2026-08-06T09:00:00Z","category":"vaporized"}',
            '{"version":1,"type":"sweepAction","at":"2026-08-06T09:00:00Z"}',
            '{"version":1,"type":"sweepAction","category":"stoppedOrphan"}',
            '{"version":1,"type":"sweepAction","at":"not-an-instant","category":"stoppedOrphan"}',
            actionLine('stoppedOrphan', 'box-1', 'tracked', 'task-1', 5)
        ])

        and: 'FR5 of harden-logging-observability: a dropped timestamp leaves a DEBUG trace'
        def logs = LogCaptureSupport.attach(SweepActionAggregator, Level.DEBUG)

        when:
        def window = aggregator.aggregate(tempDir, INSTANCE, TODAY, 1)
        def events = List.copyOf(logs.list)
        logs.detach()

        then:
        window.rows()*.objectName() == ['box-1']
        window.total() == 1

        and:
        def traces = events.findAll {
            it.formattedMessage.contains('unreadable instant')
        }
        traces.size() == 1
        traces[0].level == Level.DEBUG
    }

    // NFR-O2: a verdict that measured no age renders as absent, not as zero.
    def "a null ageSeconds reads back as no measured age"() {
        given:
        writeLedgerFile(TODAY, [
            '''{"version":1,"type":"sweepAction","at":"2026-08-06T09:00:00Z","objectName":"box-1","role":"main-box","mode":"tracked","taskKey":"task-1","category":"stoppedOrphan","reason":"unowned running","ageSeconds":null}'''
        ])

        when:
        def window = aggregator.aggregate(tempDir, INSTANCE, TODAY, 1)

        then:
        window.rows()[0].ageSeconds() == null
    }

    // NFR-R2 of add-serve-observability: the torn tail of a live append is legal.
    def "a torn last line is skipped, not an error"() {
        given:
        writeLedgerFileRaw(TODAY,
                actionLine('stoppedOrphan', 'box-1', 'tracked', 'task-1', 5) + '\n{"version":1,"type":"sweepAc')

        when:
        def window = aggregator.aggregate(tempDir, INSTANCE, TODAY, 1)

        then:
        window.rows()*.objectName() == ['box-1']
    }

    def "a missing ledger file within the window is skipped, not an error"() {
        given:
        writeLedgerFile(TODAY, [
            actionLine('stoppedOrphan', 'box-1', 'tracked', 'task-1', 5)
        ])

        when:
        def window = aggregator.aggregate(tempDir, INSTANCE, TODAY, 7)

        then:
        window.rows().size() == 1
    }

    def "a window with no readable file at all comes back empty"() {
        expect:
        aggregator.aggregate(tempDir, INSTANCE, TODAY, 7) == SweepActionWindow.EMPTY
    }

    // NFR-O3: the window is exactly windowDays long, so an older file cannot leak in.
    def "the window includes exactly the last windowDays days"() {
        given:
        writeLedgerFile(TODAY.minusDays(2), [
            actionLine('disposedAged', 'too-old', 'manual', 'task-0', 5)
        ])
        writeLedgerFile(TODAY.minusDays(1), [
            actionLine('disposedAged', 'in-range', 'manual', 'task-0', 5)
        ])

        when:
        def window = aggregator.aggregate(tempDir, INSTANCE, TODAY, 2)

        then:
        window.rows()*.objectName() == ['in-range']
    }

    private void writeLedgerFile(LocalDate date, List<String> lines) {
        writeLedgerFileRaw(date, lines.join('\n') + '\n')
    }

    private void writeLedgerFileRaw(LocalDate date, String content) {
        Path file = ObservabilityPaths.ledgerFile(tempDir, INSTANCE, date)
        Files.createDirectories(file.parent)
        Files.writeString(file, content, StandardCharsets.UTF_8)
    }

    private static String actionLine(String category, String object, String mode, String taskKey, long ageSeconds) {
        return """{"version":1,"type":"sweepAction","at":"2026-08-06T09:00:00Z","objectName":"${object}",\
"role":"main-box","mode":"${mode}","taskKey":"${taskKey}","category":"${category}",\
"reason":"unowned running","ageSeconds":${ageSeconds}}"""
    }
}
