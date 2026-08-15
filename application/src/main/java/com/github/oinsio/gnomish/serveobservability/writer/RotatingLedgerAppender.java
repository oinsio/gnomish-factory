package com.github.oinsio.gnomish.serveobservability.writer;

import com.github.oinsio.gnomish.serveobservability.LedgerLine;
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/**
 * Daily UTC rotation by name switch, layered over {@link LedgerAppender} (design D7,
 * FR14): before every {@link #append}, computes "today" in UTC from an injected {@link
 * Clock} and retargets the delegate to that day's {@code ledger-YYYY-MM-DD.jsonl} file
 * (via {@link ObservabilityPaths#ledgerFile}) whenever the UTC day has changed since the
 * last append. The delegate does the actual write; this class owns only "which
 * filename do new appends go to now" — the live file is never renamed, so external
 * tails never chase renames (FR14).
 *
 * <p>Filename formatting is not duplicated here: {@link ObservabilityPaths#ledgerFile}
 * is the single place that turns a {@link LocalDate} into a ledger filename, shared with
 * {@link LedgerRetentionSweeper}'s parsing of the same pattern.
 *
 * <p>Mirrors the module's decorator convention (e.g. the tracker health decorator,
 * design D12): a thin wrapper adding one cross-cutting concern around an existing
 * collaborator without changing its contract. {@link LedgerAppender} stays
 * single-responsibility ("append safely to a path"); this class adds "which path,
 * based on the day".
 *
 * <p>Implements FR14 of add-serve-observability.
 */
public final class RotatingLedgerAppender {

    private final LedgerAppender delegate;
    private final Path homeDir;
    private final String instanceName;
    private final Clock clock;
    private @Nullable LocalDate currentDate;

    /**
     * @param delegate the underlying appender every write is forwarded to after any
     *     needed retarget; its initial target is a placeholder — the first {@link
     *     #append} call always retargets before writing, since no UTC day has been
     *     recorded yet; never null
     * @param homeDir the user's home directory passed to {@link
     *     ObservabilityPaths#ledgerFile}; never null
     * @param instanceName the configured instance name (design D2); never null
     * @param clock supplies the current instant used to compute "today" in UTC on every
     *     append; never null
     */
    public RotatingLedgerAppender(LedgerAppender delegate, Path homeDir, String instanceName, Clock clock) {
        this.delegate = delegate;
        this.homeDir = homeDir;
        this.instanceName = instanceName;
        this.clock = clock;
    }

    /**
     * Retargets the delegate to today's UTC ledger file if the UTC day has changed since
     * the last append, then appends {@code line} through it. Safe to call from any
     * number of threads concurrently: the day check and the append are serialized
     * together, so a rotation never races an in-flight append (mirrors {@link
     * LedgerAppender}'s own {@code synchronized} discipline, design D8).
     *
     * @param line the ledger line to append; never null
     * @throws IOException if the underlying append fails
     */
    public synchronized void append(LedgerLine line) throws IOException {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!today.equals(currentDate)) {
            currentDate = today;
            delegate.retarget(ObservabilityPaths.ledgerFile(homeDir, instanceName, today));
        }
        delegate.append(line);
    }
}
