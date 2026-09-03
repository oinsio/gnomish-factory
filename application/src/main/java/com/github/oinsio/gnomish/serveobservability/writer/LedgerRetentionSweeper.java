package com.github.oinsio.gnomish.serveobservability.writer;

import com.github.oinsio.gnomish.logtext.OperatorEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ledger retention sweep (design D7, FR15): on every snapshot-writer tick, scans the
 * observability directory for {@code ledger-YYYY-MM-DD.jsonl} files (naming per FR14) and deletes
 * every one whose filename-encoded date is more than the configured retention in the past, keyed
 * to UTC day boundaries. Runs on the writer's tick rather than a dedicated thread because that
 * thread already ticks unconditionally and the observability writer owns observability files (D7)
 * — {@code WorktreeJanitor} is worktree-specific by charter and deliberately not reused here.
 *
 * <p>Retention is keyed off the date <em>encoded in the filename</em>, never file mtime: a live
 * file's name always carries "today" per {@link #clock}, so it is never eligible for deletion
 * regardless of clock skew or when it was last written — the naming scheme alone protects it.
 * {@code retentionDays == 0} means "keep forever" (design D10) and short-circuits {@link #sweep()}
 * before any filesystem access. Filenames that do not match the ledger pattern are ignored, not
 * treated as errors (an observability directory may hold {@code snapshot.json} and future files).
 * A listing or delete failure is logged and swallowed (NFR-R1 spirit): the sweep never crashes the
 * writer thread or blocks the snapshot write it shares a tick with.
 *
 * <p>Implements FR15 of add-serve-observability.
 */
final class LedgerRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(LedgerRetentionSweeper.class);

    private static final Pattern LEDGER_FILE_NAME = Pattern.compile("ledger-(\\d{4}-\\d{2}-\\d{2})\\.jsonl");

    private final Path directory;
    private final int retentionDays;
    private final Clock clock;

    /**
     * @param directory the observability directory to scan for {@code ledger-*.jsonl} files;
     *     never null; a missing directory is treated as "nothing to sweep", not an error
     * @param retentionDays number of days a ledger file is kept before it becomes eligible for
     *     deletion ({@code factory.serve.ledger-retention-days}); {@code 0} disables the sweep
     *     (keep forever, design D10); must not be negative
     * @param clock supplies the current instant used to compute "today" in UTC on every sweep;
     *     never null
     */
    LedgerRetentionSweeper(Path directory, int retentionDays, Clock clock) {
        if (retentionDays < 0) {
            throw new IllegalArgumentException("retentionDays must not be negative");
        }
        this.directory = directory;
        this.retentionDays = retentionDays;
        this.clock = clock;
    }

    /**
     * Deletes every {@code ledger-YYYY-MM-DD.jsonl} file in {@link #directory} whose encoded date
     * is more than {@link #retentionDays} days before "today" in UTC (FR15). No-op when {@link
     * #retentionDays} is {@code 0} or the directory does not exist. Never throws: listing and
     * delete failures are logged and swallowed so a bad sweep never blocks the snapshot write
     * sharing this tick.
     */
    void sweep() {
        if (retentionDays == 0 || !Files.isDirectory(directory)) {
            return;
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate cutoff = today.minusDays(retentionDays);
        try (Stream<Path> entries = Files.list(directory)) {
            entries.filter(Files::isRegularFile).forEach(path -> sweepIfEligible(path, cutoff));
        } catch (IOException e) {
            log.warn(
                    OperatorEvent.LEDGER_RETENTION_LIST_FAILED.head() + "ledger retention sweep: failed to list {}",
                    directory,
                    e);
        }
    }

    private void sweepIfEligible(Path file, LocalDate cutoff) {
        boolean eligible = encodedDate(file.getFileName().toString())
                .filter(date -> date.isBefore(cutoff))
                .isPresent();
        if (eligible) {
            delete(file);
        }
    }

    private void delete(Path file) {
        try {
            Files.deleteIfExists(file);
            log.info("ledger retention sweep: deleted {}", file);
        } catch (IOException e) {
            log.warn(
                    OperatorEvent.LEDGER_RETENTION_DELETE_FAILED.head() + "ledger retention sweep: failed to delete {}",
                    file,
                    e);
        }
    }

    private static Optional<LocalDate> encodedDate(String fileName) {
        Matcher matcher = LEDGER_FILE_NAME.matcher(fileName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(matcher.group(1)));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
