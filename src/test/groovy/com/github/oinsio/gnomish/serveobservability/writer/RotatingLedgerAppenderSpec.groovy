package com.github.oinsio.gnomish.serveobservability.writer

import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link RotatingLedgerAppender}: daily UTC rotation by name switch (design D7, FR14) —
 * appends land in today's {@code ledger-YYYY-MM-DD.jsonl} file, the same UTC day reuses
 * the file without retargeting, and crossing the UTC day boundary switches subsequent
 * appends to the new day's file while the previous day's file is left untouched, never
 * renamed.
 *
 * <p>Implements FR14 of add-serve-observability.
 */
class RotatingLedgerAppenderSpec extends Specification implements LifecycleLineFixture {

    @TempDir
    Path homeDir

    private static final String INSTANCE_NAME = 'instance-1'

    def "first append picks today's UTC date-named ledger file"() {
        given:
        def clock = Clock.fixed(Instant.parse('2026-08-03T10:00:00Z'), ZoneOffset.UTC)
        def appender = rotatingAppender(clock)

        when:
        appender.append(lifecycleLine('started'))

        then:
        def expected = ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, java.time.LocalDate.parse('2026-08-03'))
        Files.exists(expected)
        Files.readString(expected).contains('started')
    }

    def "a subsequent append on the same UTC day reuses the same file"() {
        given:
        def clock = new StepClock([
            Instant.parse('2026-08-03T10:00:00Z'),
            Instant.parse('2026-08-03T23:59:00Z')
        ])
        def appender = rotatingAppender(clock)

        when:
        appender.append(lifecycleLine('started'))
        appender.append(lifecycleLine('stopped'))

        then:
        def file = ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, java.time.LocalDate.parse('2026-08-03'))
        def lines = Files.readString(file).split('\n')
        lines.length == 2
    }

    def "an append after crossing the UTC day boundary retargets to the new day's file, leaving the previous day's file untouched"() {
        given:
        def clock = new StepClock([
            Instant.parse('2026-08-03T23:59:59Z'),
            Instant.parse('2026-08-04T00:00:01Z')
        ])
        def appender = rotatingAppender(clock)

        when:
        appender.append(lifecycleLine('started'))
        appender.append(lifecycleLine('stopped'))

        then:
        def dayOne = ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, java.time.LocalDate.parse('2026-08-03'))
        def dayTwo = ObservabilityPaths.ledgerFile(homeDir, INSTANCE_NAME, java.time.LocalDate.parse('2026-08-04'))
        Files.exists(dayOne)
        Files.readString(dayOne).contains('started')
        !Files.readString(dayOne).contains('stopped')
        Files.exists(dayTwo)
        Files.readString(dayTwo).contains('stopped')
        !Files.readString(dayTwo).contains('started')
    }

    private RotatingLedgerAppender rotatingAppender(Clock clock) {
        // The delegate's initial target is a placeholder: rotateIfNeeded always fires on
        // the very first append (no prior UTC day recorded yet), so it is retargeted
        // before anything is ever written to it.
        def placeholder = homeDir.resolve('ledger-uninitialized.jsonl')
        new RotatingLedgerAppender(new LedgerAppender(placeholder, new LedgerJsonMapper()), homeDir, INSTANCE_NAME, clock)
    }

    // A minimal Clock stub returning a pre-scripted sequence of instants, one per call —
    // mirrors SnapshotWriterSpec's StepClock to make the UTC day boundary deterministic.
    static class StepClock extends Clock {
        private final Iterator<Instant> instants
        StepClock(List<Instant> instants) {
            this.instants = instants.iterator()
        }
        @Override Instant instant() {
            instants.next()
        }
        @Override ZoneOffset getZone() {
            ZoneOffset.UTC
        }
        @Override Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException()
        }
    }
}
