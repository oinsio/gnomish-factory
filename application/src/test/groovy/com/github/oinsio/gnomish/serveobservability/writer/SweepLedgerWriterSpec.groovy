package com.github.oinsio.gnomish.serveobservability.writer

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickRecord
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdict
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link SweepLedgerWriter}, task 6.2 of add-serve-sandbox-lifecycle (NFR-O2): one line per stop
 * or dispose, one summary line per tick, nothing at all for untouched objects, and an append
 * failure that never reaches the sweep that already acted.
 */
class SweepLedgerWriterSpec extends Specification implements RotatingLedgerAppenderFixture {

    static final Instant NOW = Instant.parse('2026-08-06T09:00:00Z')
    static final InstanceInfo INSTANCE = new InstanceInfo('gnome-1', 'host1', '1.0.0')
    static final String INSTANCE_NAME = 'gnome-1'

    @TempDir
    Path homeDir

    def clock = Clock.fixed(NOW, ZoneOffset.UTC)

    private SweepLedgerWriter writer() {
        new SweepLedgerWriter(ledgerAppenderFor(homeDir, INSTANCE_NAME, NOW), INSTANCE, clock)
    }

    private List<String> ledgerLines() {
        def file = ledgerFileFor(homeDir, INSTANCE_NAME, NOW)
        Files.exists(file) ? Files.readAllLines(file).findAll {
            !it.isBlank()
        } : []
    }

    private static SweepVerdict verdict(SweepVerdictCategory category, Duration age = Duration.ofMinutes(15)) {
        new SweepVerdict(category, 'gnomish-task-1-box', 'main-box', 'tracked', 'task-1', 'unowned running', age)
    }

    // NFR-O2: every stop and dispose becomes one line carrying object, role, task, reason, age.
    def "an acting verdict becomes one sweepAction line"() {
        when:
        writer().onVerdict(verdict(SweepVerdictCategory.STOPPED_ORPHAN))

        then:
        ledgerLines().size() == 1
        ledgerLines()[0].contains('"type":"sweepAction"')
        ledgerLines()[0].contains('"category":"stoppedOrphan"')
        ledgerLines()[0].contains('"objectName":"gnomish-task-1-box"')
        ledgerLines()[0].contains('"taskKey":"task-1"')
        ledgerLines()[0].contains('"reason":"unowned running"')
        ledgerLines()[0].contains('"ageSeconds":900')
    }

    def "each acting category is written"() {
        when:
        writer().onVerdict(verdict(category))

        then:
        ledgerLines().size() == 1

        where:
        category << [
            SweepVerdictCategory.STOPPED_ORPHAN,
            SweepVerdictCategory.DISPOSED_AGED,
            SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE
        ]
    }

    // NFR-O2: "untouched objects SHALL never be itemized in the ledger" — an hourly sweep over a
    //     busy host must cost one line per tick, not one per container.
    def "an untouched verdict writes nothing"() {
        when:
        writer().onVerdict(verdict(category))

        then:
        ledgerLines().isEmpty()

        where:
        category << [
            SweepVerdictCategory.CHECKED_ALIVE,
            SweepVerdictCategory.KEPT_UNDER_THRESHOLD,
            SweepVerdictCategory.SKIPPED_NO_VERDICT
        ]
    }

    // NFR-O2: the tick line carries ALL six counts, including the untouched ones.
    def "a completed tick becomes one sweepTick line carrying every count"() {
        given:
        def record = new SweepTickRecord(
                NOW,
                [
                    (SweepVerdictCategory.CHECKED_ALIVE): 4,
                    (SweepVerdictCategory.SKIPPED_NO_VERDICT): 2
                ],
                [],
                0,
                1)

        when:
        writer().onTickCompleted(record)

        then:
        ledgerLines().size() == 1
        ledgerLines()[0].contains('"type":"sweepTick"')
        ledgerLines()[0].contains('"checkedAlive":4')
        ledgerLines()[0].contains('"skippedNoVerdict":2')
        ledgerLines()[0].contains('"stoppedOrphan":0')
    }

    // NFR-O2: the tick line is stamped with the TICK's own instant, not the write instant — the
    //     summary describes when the pass finished.
    def "the tick line carries the record's own instant"() {
        given:
        def tickAt = Instant.parse('2026-08-06T08:30:00Z')

        when:
        writer().onTickCompleted(new SweepTickRecord(tickAt, [:], [], 0, 0))

        then:
        ledgerLines()[0].contains('"at":"2026-08-06T08:30:00Z"')
    }

    // NFR-R3: an observability write must never fail a sweep tick that already stopped a container.
    //
    // FR15 of harden-logging-observability: both append edges (an action line and a tick line)
    // leave an ERROR carrying the catalog code, so a silently ledger-less sweep is impossible.
    def "an append failure is swallowed, not propagated, and leaves one ERROR per lost line"() {
        given: 'a regular file where the ledger directory belongs, so every append fails'
        Files.writeString(homeDir.resolve('.gnomish'), 'not a directory')
        def writer = writer()
        def logs = LogCaptureSupport.attach(SweepLedgerWriter)

        when:
        writer.onVerdict(verdict(SweepVerdictCategory.STOPPED_ORPHAN))
        writer.onTickCompleted(new SweepTickRecord(NOW, [:], [], 0, 0))

        then:
        noExceptionThrown()
        def events = logs.list.findAll {
            it.formattedMessage.startsWith(OperatorEvent.SWEEP_LEDGER_APPEND_FAILED.head())
        }
        events.size() == 2
        events.every { it.level == Level.ERROR }

        cleanup:
        logs.detach()
    }
}
