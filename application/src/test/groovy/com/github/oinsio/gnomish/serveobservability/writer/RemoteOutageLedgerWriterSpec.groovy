package com.github.oinsio.gnomish.serveobservability.writer

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.serve.RemoteOutageClosedOutage
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link RemoteOutageLedgerWriter}, task 7.4 of add-base-ref-resolution (NFR-O1, NFR-O3): one
 * {@code remoteOutage} line per closed outage, and an append failure that never propagates.
 */
class RemoteOutageLedgerWriterSpec extends Specification implements RotatingLedgerAppenderFixture {

    static final Instant NOW = Instant.parse('2026-08-06T09:00:00Z')
    static final InstanceInfo INSTANCE = new InstanceInfo('gnome-1', 'host1', '1.0.0')
    static final String INSTANCE_NAME = 'gnome-1'

    @TempDir
    Path homeDir

    private RemoteOutageLedgerWriter writer() {
        new RemoteOutageLedgerWriter(ledgerAppenderFor(homeDir, INSTANCE_NAME, NOW), INSTANCE)
    }

    private List<String> ledgerLines() {
        def file = ledgerFileFor(homeDir, INSTANCE_NAME, NOW)
        Files.exists(file) ? Files.readAllLines(file) : []
    }

    def "one remoteOutage line is appended per closed outage"() {
        given:
        def outage = new RemoteOutageClosedOutage(
                'origin', Instant.parse('2026-08-06T08:00:00Z'), Instant.parse('2026-08-06T08:30:00Z'), 5, 2,
                'connection refused')

        when:
        writer().accept(outage)

        then:
        def lines = ledgerLines()
        lines.size() == 1
        lines[0].contains('"type":"remoteOutage"')
        lines[0].contains('"target":"origin"')
        lines[0].contains('"probeCount":5')
        lines[0].contains('"releasedClaims":2')
    }

    // NFR-R3: an append failure never reaches the gate that already closed.
    def "an append failure is swallowed, not propagated, and leaves one ERROR carrying the catalog code"() {
        given: 'a regular file where the ledger directory belongs, so every append fails'
        Files.writeString(homeDir.resolve('.gnomish'), 'not a directory')
        def logs = LogCaptureSupport.attach(RemoteOutageLedgerWriter)
        def outage = new RemoteOutageClosedOutage(
                'origin', Instant.parse('2026-08-06T08:00:00Z'), Instant.parse('2026-08-06T08:30:00Z'), 1, 0, 'boom')

        when:
        writer().accept(outage)

        then:
        noExceptionThrown()
        def events = logs.list.findAll {
            it.formattedMessage.startsWith(OperatorEvent.REMOTE_OUTAGE_LEDGER_APPEND_FAILED.head())
        }
        events.size() == 1
        events[0].level == Level.ERROR

        cleanup:
        logs.detach()
    }
}
