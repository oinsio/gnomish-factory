package com.github.oinsio.gnomish.serveobservability.writer

import static com.github.oinsio.gnomish.serveobservability.ObservabilityPaths.ledgerFile

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper
import java.nio.file.Path
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Shared test fixture for the ledger-writer specs: builds a {@link RotatingLedgerAppender} over an
 * instance's serve directory — frozen at one instant, or on the spec's own time source — and
 * resolves the ledger file it will write to for a given instant, so each writer spec only supplies
 * its own serve directory and writer construction.
 */
trait RotatingLedgerAppenderFixture {

    RotatingLedgerAppender ledgerAppenderFor(Path serveDir, Instant now) {
        ledgerAppenderOn(serveDir, new VirtualClock(now))
    }

    RotatingLedgerAppender ledgerAppenderOn(Path serveDir, InstantSource clock) {
        new RotatingLedgerAppender(
                new LedgerAppender(serveDir.resolveSibling('placeholder'), new LedgerJsonMapper()),
                serveDir, clock)
    }

    Path ledgerFileFor(Path serveDir, Instant now) {
        ledgerFile(serveDir, LocalDate.ofInstant(now, ZoneOffset.UTC))
    }
}
