package com.github.oinsio.gnomish.serveobservability.writer

import ch.qos.logback.classic.Level
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.logtext.OperatorEvent
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link LifecycleLedgerWriter}: the daemon startup/shutdown write point for the
 * ledger's {@code lifecycle} line (design D6, FR12) — {@link
 * LifecycleLedgerWriter#writeStarted() writeStarted} and {@link
 * LifecycleLedgerWriter#writeStopped(String) writeStopped(reason)} assemble the line
 * via {@code LifecycleLineAssembler} and append it through the shared {@link
 * RotatingLedgerAppender}.
 *
 * <p>Implements FR12 of add-serve-observability.
 */
class LifecycleLedgerWriterSpec extends Specification implements RotatingLedgerAppenderFixture {

    @TempDir
    Path homeDir

    private static final String INSTANCE_NAME = 'gnomish'
    private static final InstanceInfo INSTANCE = new InstanceInfo('gnomish-ab12cd', 'worker-1', '0.1.0')
    private static final ObjectMapper JSON = new ObjectMapper()

    private LifecycleLedgerWriter writer(Instant now) {
        def appender = ledgerAppenderFor(homeDir, INSTANCE_NAME, now)
        return new LifecycleLedgerWriter(appender, INSTANCE, Clock.fixed(now, ZoneOffset.UTC))
    }

    private Path fileFor(Instant now) {
        return ledgerFileFor(homeDir, INSTANCE_NAME, now)
    }

    def "writeStarted appends exactly one started lifecycle line with no reason"() {
        given:
        def now = Instant.parse('2026-08-03T10:00:00Z')

        when:
        writer(now).writeStarted()

        then:
        def lines = Files.readString(fileFor(now)).split('\n').findAll {
            !it.isBlank()
        }
        lines.size() == 1
        def json = JSON.readTree(lines[0])
        json.get('type').asText() == 'lifecycle'
        json.get('event').asText() == 'started'
        json.get('at').asText() == '2026-08-03T10:00:00Z'
        json.get('instance').get('instanceId').asText() == 'gnomish-ab12cd'
        !json.has('reason') || json.get('reason').isNull()
    }

    def "writeStopped appends exactly one stopped lifecycle line carrying the reason"() {
        given:
        def now = Instant.parse('2026-08-03T12:00:00Z')

        when:
        writer(now).writeStopped('signal')

        then:
        def lines = Files.readString(fileFor(now)).split('\n').findAll {
            !it.isBlank()
        }
        lines.size() == 1
        def json = JSON.readTree(lines[0])
        json.get('type').asText() == 'lifecycle'
        json.get('event').asText() == 'stopped'
        json.get('reason').asText() == 'signal'
    }

    // NFR-R1: a write failure (a blocked ledger directory) must never escape writeStarted/
    // writeStopped and crash the daemon.
    //
    // FR15 of harden-logging-observability: swallowed is not silent — the durable plane lost a
    // line, so the pin is the catalog code and the ERROR level, not the sentence.
    def "writeStarted swallows an IOException from a blocked ledger directory, leaving an ERROR trace"() {
        given:
        def now = Instant.parse('2026-08-03T10:00:00Z')
        Files.writeString(homeDir.resolve('.gnomish'), 'not a directory')
        def logs = LogCaptureSupport.attach(LifecycleLedgerWriter)

        when:
        writer(now).writeStarted()

        then:
        noExceptionThrown()
        def event = logs.list.find {
            it.formattedMessage.startsWith(OperatorEvent.LIFECYCLE_LEDGER_APPEND_FAILED.head())
        }
        event != null
        event.level == Level.ERROR

        cleanup:
        logs.detach()
    }

    def "writeStarted then writeStopped append two lines in order"() {
        given:
        def now = Instant.parse('2026-08-03T09:00:00Z')
        def w = writer(now)

        when:
        w.writeStarted()
        w.writeStopped('drainComplete')

        then:
        def lines = Files.readString(fileFor(now)).split('\n').findAll {
            !it.isBlank()
        }
        lines.size() == 2
        JSON.readTree(lines[0]).get('event').asText() == 'started'
        JSON.readTree(lines[1]).get('event').asText() == 'stopped'
    }
}
