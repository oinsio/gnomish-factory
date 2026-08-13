package com.github.oinsio.gnomish.serveobservability.writer

import static com.github.oinsio.gnomish.serveobservability.ObservabilityPaths.ledgerFile

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.RunSummaryAccumulator
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link RunSummaryLedgerWriter}: the drain-completion write point for the ledger's {@code
 * runSummary} line (design D6, FR13) — {@link RunSummaryLedgerWriter#write} assembles the line
 * from a {@link RunSummaryAccumulator}'s totals and appends it through the shared {@link
 * RotatingLedgerAppender}, writing exactly one line per call.
 *
 * <p>Implements FR13, D6 of add-serve-observability.
 */
class RunSummaryLedgerWriterSpec extends Specification {

    @TempDir
    Path homeDir

    private static final String INSTANCE_NAME = 'gnomish'
    private static final InstanceInfo INSTANCE = new InstanceInfo('gnomish-ab12cd', 'worker-1', '0.1.0')
    private static final ObjectMapper JSON = new ObjectMapper()

    private RunSummaryLedgerWriter writer(Instant now) {
        def appender = new RotatingLedgerAppender(
                new LedgerAppender(homeDir.resolve('placeholder'), new LedgerJsonMapper()),
                homeDir, INSTANCE_NAME, Clock.fixed(now, ZoneOffset.UTC))
        return new RunSummaryLedgerWriter(appender, INSTANCE, Clock.fixed(now, ZoneOffset.UTC))
    }

    private Path ledgerFileFor(Instant now) {
        return ledgerFile(homeDir, INSTANCE_NAME, LocalDate.ofInstant(now, ZoneOffset.UTC))
    }

    private static TaskState delivered(Map<String, TokenUsage> tokensByModel) {
        return new TaskState(new Position.AtStage('build'), 0, [], new ExecutorUsage(null, [], tokensByModel))
    }

    def "write appends exactly one runSummary line aggregating the accumulator's totals"() {
        given:
        def startedAt = Instant.parse('2026-08-03T10:00:00Z')
        def finishedAt = Instant.parse('2026-08-03T10:05:30Z')
        def accumulator = new RunSummaryAccumulator()
        accumulator.record(new TakeResult.Delivered(
                        delivered(['claude-x': new TokenUsage(100L, 50L, 10L, 5L)]), 'shipped it'))

        when:
        writer(finishedAt).write(accumulator, startedAt)

        then:
        def lines = Files.readString(ledgerFileFor(finishedAt)).split('\n').findAll {
            !it.isBlank()
        }
        lines.size() == 1
        def json = JSON.readTree(lines[0])
        json.get('type').asText() == 'runSummary'
        json.get('startedAt').asText() == '2026-08-03T10:00:00Z'
        json.get('finishedAt').asText() == '2026-08-03T10:05:30Z'
        json.get('wallMillis').asLong() == 330_000L
        json.get('counts').get('delivered').asInt() == 1
        json.get('tokensByModel').get('claude-x').get('input').asLong() == 100L
    }

    // NFR-R1: a write failure (a blocked ledger directory) must never escape write() and crash
    // the drain run it is completing.
    def "swallows an IOException from a blocked ledger directory"() {
        given:
        def now = Instant.parse('2026-08-03T09:00:00Z')
        Files.writeString(homeDir.resolve('.gnomish'), 'not a directory')

        when:
        writer(now).write(new RunSummaryAccumulator(), now)

        then:
        noExceptionThrown()
    }

    def "write appends a zero-outcome runSummary line for an accumulator with nothing recorded"() {
        given:
        def now = Instant.parse('2026-08-03T09:00:00Z')

        when:
        writer(now).write(new RunSummaryAccumulator(), now)

        then:
        def lines = Files.readString(ledgerFileFor(now)).split('\n').findAll {
            !it.isBlank()
        }
        lines.size() == 1
        def json = JSON.readTree(lines[0])
        json.get('counts').get('delivered').asInt() == 0
        json.get('counts').get('awaitingHuman').asInt() == 0
        json.get('tokensByModel').isEmpty()
    }
}
