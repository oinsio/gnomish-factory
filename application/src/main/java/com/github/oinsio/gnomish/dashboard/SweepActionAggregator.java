package com.github.oinsio.gnomish.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory;
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths;
import com.github.oinsio.gnomish.serveobservability.json.LedgerLineReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Reads the sweep's {@code sweepAction} lines out of the last N daily ledger files for the
 * sandbox hygiene section's recent-actions table (NFR-O3, UX1 of add-serve-sandbox-lifecycle).
 * Sibling of {@link LedgerAggregator}, which reads {@code taskOutcome} lines out of the same
 * files: kept apart because the two answer different questions over different line kinds, and a
 * merged reader would couple the hygiene section's degradation to the history section's.
 *
 * <p>Degradation matches {@link LedgerAggregator}'s exactly: a day with no ledger file is skipped,
 * a torn last line is tolerated by {@link LedgerLineReader}, and a well-formed line whose {@code
 * category} is missing or unrecognized (a newer factory version's vocabulary) is skipped rather
 * than raised — the render must survive it.
 *
 * <p>Implements NFR-O3, UX1 of add-serve-sandbox-lifecycle.
 */
public final class SweepActionAggregator {

    /** How many actions the table carries, newest first, before truncating. */
    public static final int MAX_ACTIONS = 20;

    private final LedgerLineReader reader;

    /** Builds an aggregator backed by a fresh {@link LedgerLineReader} instance. */
    public SweepActionAggregator() {
        this.reader = new LedgerLineReader();
    }

    /**
     * Collects the window's sweep actions, newest first.
     *
     * @param homeDir the user's home directory; never null
     * @param instanceName the configured instance name; never null
     * @param today the reference UTC date the window ends at (inclusive); never null
     * @param windowDays how many trailing days to include; must be positive
     * @return the collected rows, newest first, bounded at {@link #MAX_ACTIONS}, with the
     *     pre-truncation total
     * @throws IOException if a ledger file within the window has a malformed non-tail line
     */
    public SweepActionWindow aggregate(Path homeDir, String instanceName, LocalDate today, int windowDays)
            throws IOException {
        List<SweepActionRow> rows = new ArrayList<>();
        for (int offset = windowDays - 1; offset >= 0; offset--) {
            Path file = ObservabilityPaths.ledgerFile(homeDir, instanceName, today.minusDays(offset));
            if (!Files.exists(file)) {
                continue;
            }
            collect(reader.read(file), rows);
        }
        List<SweepActionRow> newestFirst = rows.reversed();
        return new SweepActionWindow(
                newestFirst.subList(0, Math.min(MAX_ACTIONS, newestFirst.size())), newestFirst.size());
    }

    private static void collect(List<JsonNode> lines, List<SweepActionRow> rows) {
        for (JsonNode line : lines) {
            if (!"sweepAction".equals(line.path("type").asText(null))) {
                continue;
            }
            SweepActionRow row = toRow(line);
            if (row != null) {
                rows.add(row);
            }
        }
    }

    private static @Nullable SweepActionRow toRow(JsonNode line) {
        SweepVerdictCategory category = parseCategory(line.path("category").asText(null));
        Instant at = parseInstant(line.path("at").asText(null));
        if (category == null || at == null) {
            return null;
        }
        JsonNode age = line.path("ageSeconds");
        return new SweepActionRow(
                at,
                line.path("objectName").asText(""),
                line.path("role").asText(""),
                line.path("mode").asText(""),
                line.path("taskKey").asText(""),
                category,
                line.path("reason").asText(""),
                age.isIntegralNumber() ? age.longValue() : null);
    }

    /**
     * Returns null (rather than throwing) for a missing or unrecognized category, so one line from
     * a newer factory version cannot crash the dashboard render — the same forward-compatibility
     * rule {@link LedgerAggregator}'s outcome parsing follows.
     */
    private static @Nullable SweepVerdictCategory parseCategory(@Nullable String category) {
        if (category == null) {
            return null;
        }
        return switch (category) {
            case "stoppedOrphan" -> SweepVerdictCategory.STOPPED_ORPHAN;
            case "disposedAged" -> SweepVerdictCategory.DISPOSED_AGED;
            case "disposedReconstructible" -> SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE;
            default -> null;
        };
    }

    private static @Nullable Instant parseInstant(@Nullable String at) {
        if (at == null) {
            return null;
        }
        try {
            return Instant.parse(at);
        } catch (DateTimeException malformed) {
            return null;
        }
    }
}
