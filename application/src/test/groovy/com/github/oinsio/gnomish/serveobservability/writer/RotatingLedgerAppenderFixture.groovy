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
 * RotatingLedgerAppender} over a temp home directory and resolves the ledger file it will write
 * to for a given instant, so each writer spec only supplies its own instance name and writer
 * construction.
 */
trait RotatingLedgerAppenderFixture {

    RotatingLedgerAppender ledgerAppenderFor(Path homeDir, String instanceName, Instant now) {
        new RotatingLedgerAppender(
                new LedgerAppender(homeDir.resolve('placeholder'), new LedgerJsonMapper()),
                homeDir, instanceName, Clock.fixed(now, ZoneOffset.UTC))
    }

    Path ledgerFileFor(Path homeDir, String instanceName, Instant now) {
        ledgerFile(homeDir, instanceName, LocalDate.ofInstant(now, ZoneOffset.UTC))
    }
}
