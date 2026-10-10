package com.github.oinsio.gnomish.serveobservability.writer

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link RotatingLedgerAppender}: daily UTC rotation by name switch (design D7, FR14) —
 * appends land in today's {@code ledger-YYYY-MM-DD.jsonl} file, the same UTC day reuses
 * the file without retargeting, and crossing the UTC day boundary switches subsequent
 * appends to the new day's file while the previous day's file is left untouched, never
 * renamed.
 *
 * <p>Implements FR14 of add-serve-observability; FR10 of add-project-registry (the files land in
 * the serve directory the appender is handed).
 */
class RotatingLedgerAppenderSpec extends Specification implements LifecycleLineFixture {

    @TempDir
    Path serveDir

    def "first append picks today's UTC date-named ledger file"() {
        given:
        def clock = new VirtualClock(Instant.parse('2026-08-03T10:00:00Z'))
        def appender = rotatingAppender(clock)

        when:
        appender.append(lifecycleLine('started'))

        then:
        def expected = ObservabilityPaths.ledgerFile(serveDir, LocalDate.parse('2026-08-03'))
        Files.exists(expected)
        Files.readString(expected).contains('started')
    }

    def "a subsequent append on the same UTC day reuses the same file"() {
        given:
        def clock = new VirtualClock(Instant.parse('2026-08-03T10:00:00Z'))
        def appender = rotatingAppender(clock)

        when: 'one append early in the day, the next a minute before midnight'
        appender.append(lifecycleLine('started'))
        clock.advance(Duration.between(clock.instant(), Instant.parse('2026-08-03T23:59:00Z')))
        appender.append(lifecycleLine('stopped'))

        then:
        def file = ObservabilityPaths.ledgerFile(serveDir, LocalDate.parse('2026-08-03'))
        def lines = Files.readString(file).split('\n')
        lines.length == 2
    }

    def "an append after crossing the UTC day boundary retargets to the new day's file, leaving the previous day's file untouched"() {
        given:
        def clock = new VirtualClock(Instant.parse('2026-08-03T23:59:59Z'))
        def appender = rotatingAppender(clock)

        when: 'one append a second before midnight, the next two seconds later, on the new day'
        appender.append(lifecycleLine('started'))
        clock.advance(Duration.ofSeconds(2))
        appender.append(lifecycleLine('stopped'))

        then:
        def dayOne = ObservabilityPaths.ledgerFile(serveDir, LocalDate.parse('2026-08-03'))
        def dayTwo = ObservabilityPaths.ledgerFile(serveDir, LocalDate.parse('2026-08-04'))
        Files.exists(dayOne)
        Files.readString(dayOne).contains('started')
        !Files.readString(dayOne).contains('stopped')
        Files.exists(dayTwo)
        Files.readString(dayTwo).contains('stopped')
        !Files.readString(dayTwo).contains('started')
    }

    private RotatingLedgerAppender rotatingAppender(InstantSource clock) {
        // The delegate's initial target is a placeholder: rotateIfNeeded always fires on
        // the very first append (no prior UTC day recorded yet), so it is retargeted
        // before anything is ever written to it.
        def placeholder = serveDir.resolve('ledger-uninitialized.jsonl')
        new RotatingLedgerAppender(new LedgerAppender(placeholder, new LedgerJsonMapper()), serveDir, clock)
    }
}
