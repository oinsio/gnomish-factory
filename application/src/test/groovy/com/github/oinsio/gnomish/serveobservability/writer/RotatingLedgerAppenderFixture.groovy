package com.github.oinsio.gnomish.serveobservability.writer

import static com.github.oinsio.gnomish.serveobservability.ObservabilityPaths.ledgerFile

import com.github.oinsio.gnomish.serveobservability.json.LedgerJsonMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Shared test fixture for the ledger-writer specs: builds a fixed-clock {@link
 * RotatingLedgerAppender} over an instance's serve directory and resolves the ledger file it will
 * write to for a given instant, so each writer spec only supplies its own serve directory and
 * writer construction.
 */
trait RotatingLedgerAppenderFixture {

    RotatingLedgerAppender ledgerAppenderFor(Path serveDir, Instant now) {
        new RotatingLedgerAppender(
                new LedgerAppender(serveDir.resolveSibling('placeholder'), new LedgerJsonMapper()),
                serveDir, Clock.fixed(now, ZoneOffset.UTC))
    }

    Path ledgerFileFor(Path serveDir, Instant now) {
        ledgerFile(serveDir, LocalDate.ofInstant(now, ZoneOffset.UTC))
    }
}
