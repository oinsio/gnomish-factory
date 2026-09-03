package com.github.oinsio.gnomish.serveobservability.writer

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Requires
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link LedgerRetentionSweeper#sweep}: the retention-sweep policy (design D7, FR15) —
 * ledger files whose filename-encoded UTC date is more than the configured retention
 * in the past are deleted, files within retention are kept, retention {@code 0}
 * disables the sweep entirely, filenames that don't match the {@code
 * ledger-YYYY-MM-DD.jsonl} pattern are ignored rather than crashing the sweep, and a
 * delete failure is logged and swallowed rather than propagated (NFR-R1 spirit,
 * consistent with task 3.6).
 *
 * <p>Implements FR15 of add-serve-observability.
 */
class LedgerRetentionSweeperSpec extends Specification {

    @TempDir
    Path dir

    // "today" per the sweeper's clock, fixed at UTC noon so date arithmetic is unambiguous.
    def clock = Clock.fixed(Instant.parse('2026-08-03T12:00:00Z'), ZoneOffset.UTC)

    def "deletes ledger files whose encoded date is more than the retention days in the past"() {
        given:
        def stale = dir.resolve('ledger-2026-06-01.jsonl')
        Files.writeString(stale, 'stale')
        def sweeper = new LedgerRetentionSweeper(dir, 30, clock)

        when:
        sweeper.sweep()

        then:
        !Files.exists(stale)
    }

    def "keeps ledger files whose encoded date is within the retention window"() {
        given:
        def recent = dir.resolve('ledger-2026-08-01.jsonl')
        Files.writeString(recent, 'recent')
        def today = dir.resolve('ledger-2026-08-03.jsonl')
        Files.writeString(today, 'today')
        def sweeper = new LedgerRetentionSweeper(dir, 30, clock)

        when:
        sweeper.sweep()

        then:
        Files.exists(recent)
        Files.exists(today)
    }

    def "the boundary file exactly N days old is kept, N+1 days old is deleted"() {
        given:
        // clock "today" = 2026-08-03; retention = 5 days.
        def boundary = dir.resolve('ledger-2026-07-29.jsonl') // exactly 5 days ago
        def justOver = dir.resolve('ledger-2026-07-28.jsonl') // 6 days ago
        Files.writeString(boundary, 'x')
        Files.writeString(justOver, 'x')
        def sweeper = new LedgerRetentionSweeper(dir, 5, clock)

        when:
        sweeper.sweep()

        then:
        Files.exists(boundary)
        !Files.exists(justOver)
    }

    def "retention 0 disables the sweep entirely, keeping even very old files"() {
        given:
        def ancient = dir.resolve('ledger-2020-01-01.jsonl')
        Files.writeString(ancient, 'ancient')
        def sweeper = new LedgerRetentionSweeper(dir, 0, clock)

        when:
        sweeper.sweep()

        then:
        Files.exists(ancient)
    }

    def "malformed or non-matching filenames are ignored, not deleted, and don't crash the sweep"() {
        given:
        def malformed = dir.resolve('ledger-not-a-date.jsonl')
        def unrelated = dir.resolve('snapshot.json')
        def wrongExt = dir.resolve('ledger-2020-01-01.txt')
        Files.writeString(malformed, 'x')
        Files.writeString(unrelated, 'x')
        Files.writeString(wrongExt, 'x')
        def sweeper = new LedgerRetentionSweeper(dir, 1, clock)

        when:
        sweeper.sweep()

        then:
        noExceptionThrown()
        Files.exists(malformed)
        Files.exists(unrelated)
        Files.exists(wrongExt)
    }

    // FR15: retentionDays cannot be negative — the constructor fails fast rather than
    // silently misbehaving on an invalid config value.
    def "rejects a negative retentionDays"() {
        when:
        new LedgerRetentionSweeper(dir, -1, clock)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('retentionDays must not be negative')
    }

    // FR15: a filename that matches the ledger digit pattern but encodes an invalid calendar
    // date (e.g. month 13) must not crash the sweep — it is ignored like any other non-matching
    // name, not deleted.
    def "a filename with digit-shaped but calendar-invalid date is ignored, not deleted"() {
        given:
        def invalidDate = dir.resolve('ledger-2026-13-40.jsonl')
        Files.writeString(invalidDate, 'x')
        def sweeper = new LedgerRetentionSweeper(dir, 1, clock)

        when:
        sweeper.sweep()

        then:
        noExceptionThrown()
        Files.exists(invalidDate)
    }

    // POSIX permissions aren't meaningful on Windows; see the delete-failure spec above.
    @Requires({
        !System.getProperty('os.name').toLowerCase().contains('windows')
    })
    // FR15 of harden-logging-observability: "logged" is the assertion — the catalog code and the
    // WARN level, not the sentence.
    def "a directory listing failure is logged and swallowed rather than propagated"() {
        given:
        Files.writeString(dir.resolve('ledger-2020-01-01.jsonl'), 'stale')
        def original = Files.getPosixFilePermissions(dir)
        // Execute-only: Files.isDirectory still succeeds, but Files.list needs read too.
        Files.setPosixFilePermissions(dir, EnumSet.of(PosixFilePermission.OWNER_EXECUTE))
        def sweeper = new LedgerRetentionSweeper(dir, 30, clock)
        def logs = LogCaptureSupport.attach(LedgerRetentionSweeper)

        when:
        sweeper.sweep()

        then:
        noExceptionThrown()
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.LEDGER_RETENTION_LIST_FAILED.head())
        }
        event != null
        event.level == Level.WARN

        cleanup:
        logs.detach()
        Files.setPosixFilePermissions(dir, original)
    }

    // FR15: sweep() filters to REGULAR files before eligibility — a directory whose name happens
    // to match the ledger pattern must never be touched, even when it is old enough to be eligible
    // and empty enough that Files.deleteIfExists would otherwise silently succeed on it.
    def "does not touch a directory whose name matches the ledger pattern, only regular files"() {
        given:
        def staleDir = dir.resolve('ledger-2020-01-01.jsonl')
        Files.createDirectory(staleDir)
        def sweeper = new LedgerRetentionSweeper(dir, 30, clock)

        when:
        sweeper.sweep()

        then:
        Files.exists(staleDir)
    }

    def "a missing observability directory is a no-op, not a crash"() {
        given:
        def missing = dir.resolve('does-not-exist')
        def sweeper = new LedgerRetentionSweeper(missing, 30, clock)

        when:
        sweeper.sweep()

        then:
        noExceptionThrown()
    }

    // POSIX permissions aren't meaningful on Windows; this repo targets macOS/Linux (Darwin CI),
    // but the guard keeps the spec portable rather than assuming the platform.
    @Requires({
        !System.getProperty('os.name').toLowerCase().contains('windows')
    })
    // FR15: same pin on the delete edge — a retention file that survives its cutoff unnoticed is
    // exactly the silent-degradation shape this change exists to close.
    def "a delete failure is logged and swallowed rather than propagated"() {
        given:
        def stale = dir.resolve('ledger-2020-01-01.jsonl')
        Files.writeString(stale, 'stale')
        // Remove write permission on the parent directory so the delete fails with an IOException.
        def original = Files.getPosixFilePermissions(dir)
        Files.setPosixFilePermissions(dir, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        def sweeper = new LedgerRetentionSweeper(dir, 30, clock)
        def logs = LogCaptureSupport.attach(LedgerRetentionSweeper)

        when:
        sweeper.sweep()

        then:
        noExceptionThrown()
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.LEDGER_RETENTION_DELETE_FAILED.head())
        }
        event != null
        event.level == Level.WARN

        cleanup:
        logs.detach()
        Files.setPosixFilePermissions(dir, original)
    }
}
