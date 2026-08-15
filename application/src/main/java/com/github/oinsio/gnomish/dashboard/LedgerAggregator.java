package com.github.oinsio.gnomish.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.oinsio.gnomish.serveobservability.LedgerTokenUsage;
import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths;
import com.github.oinsio.gnomish.serveobservability.OutcomeCounts;
import com.github.oinsio.gnomish.serveobservability.TaskOutcome;
import com.github.oinsio.gnomish.serveobservability.json.LedgerLineReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Aggregates the last N daily ledger files into the dashboard's history-
 * section view model (task 1.3): per-day {@code taskOutcome} counts and
 * window-total tokens-by-model. Other {@link
 * com.github.oinsio.gnomish.serveobservability.LedgerLine} variants ({@code
 * lifecycle}, {@code runSummary}) are ignored. Reuses {@link
 * LedgerLineReader} for the torn-last-line tolerance (NFR-R2 of
 * add-serve-observability) — a malformed non-tail line still surfaces as an
 * {@link IOException}, unweakened; a day whose ledger file does not exist
 * (outside retention, or before the instance's first run) is silently
 * skipped rather than failing the whole window (design D5). A well-formed
 * {@code taskOutcome} line whose {@code outcome} is missing or unrecognized
 * (e.g. an outcome a newer factory version introduced) is skipped too, not
 * counted and never raised — the render must survive it (FR6, NFR-R1).
 *
 * <p>Implements FR6 of add-dashboard-page (design D5).
 */
public final class LedgerAggregator {

    /** Default history window (design D5): the last 7 UTC days. */
    public static final int DEFAULT_WINDOW_DAYS = 7;

    private final LedgerLineReader reader;

    /** Builds an aggregator backed by a fresh {@link LedgerLineReader} instance. */
    public LedgerAggregator() {
        this.reader = new LedgerLineReader();
    }

    /**
     * Aggregates the {@link #DEFAULT_WINDOW_DAYS}-day window ending at {@code today}.
     *
     * @param homeDir the user's home directory; never null
     * @param instanceName the configured instance name; never null
     * @param today the reference UTC date the window ends at (inclusive); never null
     * @return the aggregated history view
     * @throws IOException if a ledger file within the window has a malformed non-tail line (NFR-R2)
     */
    public LedgerHistoryView aggregate(Path homeDir, String instanceName, LocalDate today) throws IOException {
        return aggregate(homeDir, instanceName, today, DEFAULT_WINDOW_DAYS);
    }

    /**
     * Aggregates the {@code windowDays}-day window ending at {@code today}.
     *
     * @param homeDir the user's home directory; never null
     * @param instanceName the configured instance name; never null
     * @param today the reference UTC date the window ends at (inclusive); never null
     * @param windowDays how many trailing days to include; must be positive
     * @return the aggregated history view, oldest day first
     * @throws IOException if a ledger file within the window has a malformed non-tail line (NFR-R2)
     */
    public LedgerHistoryView aggregate(Path homeDir, String instanceName, LocalDate today, int windowDays)
            throws IOException {
        List<DayOutcomeCounts> perDay = new ArrayList<>();
        Map<String, LedgerTokenUsage> tokensByModel = new LinkedHashMap<>();
        for (int offset = windowDays - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            Path file = ObservabilityPaths.ledgerFile(homeDir, instanceName, date);
            if (!Files.exists(file)) {
                continue;
            }
            List<JsonNode> lines = reader.read(file);
            perDay.add(new DayOutcomeCounts(date, aggregateDay(lines, tokensByModel)));
        }
        return new LedgerHistoryView(perDay, tokensByModel);
    }

    private static OutcomeCounts aggregateDay(List<JsonNode> lines, Map<String, LedgerTokenUsage> tokensByModel) {
        int delivered = 0;
        int awaitingHuman = 0;
        int aborted = 0;
        int revoked = 0;
        for (JsonNode line : lines) {
            if (!"taskOutcome".equals(line.path("type").asText(null))) {
                continue;
            }
            TaskOutcome outcome = parseOutcome(line.path("outcome").asText(null));
            if (outcome == null) {
                // Missing or unrecognized outcome (e.g. written by a newer factory
                // version): skip the line and process the rest, never crash the
                // render (FR6, FR3, NFR-R1 of add-dashboard-page).
                continue;
            }
            switch (outcome) {
                case DELIVERED -> delivered++;
                case AWAITING_HUMAN -> awaitingHuman++;
                case ABORTED -> aborted++;
                case REVOKED -> revoked++;
            }
            accumulateTokens(line.path("tokensByModel"), tokensByModel);
        }
        return new OutcomeCounts(delivered, awaitingHuman, aborted, revoked);
    }

    private static void accumulateTokens(JsonNode tokensNode, Map<String, LedgerTokenUsage> totals) {
        if (!tokensNode.isObject()) {
            return;
        }
        tokensNode
                .fields()
                .forEachRemaining(
                        entry -> totals.merge(entry.getKey(), toTokenUsage(entry.getValue()), LedgerAggregator::sum));
    }

    private static LedgerTokenUsage toTokenUsage(JsonNode node) {
        return new LedgerTokenUsage(
                node.path("input").asLong(0),
                node.path("output").asLong(0),
                node.path("cacheCreation").asLong(0),
                node.path("cacheRead").asLong(0));
    }

    private static LedgerTokenUsage sum(LedgerTokenUsage a, LedgerTokenUsage b) {
        return new LedgerTokenUsage(
                a.input() + b.input(),
                a.output() + b.output(),
                a.cacheCreation() + b.cacheCreation(),
                a.cacheRead() + b.cacheRead());
    }

    /**
     * Maps a raw {@code taskOutcome.outcome} value to its {@link TaskOutcome}, or
     * {@code null} when the value is missing or unrecognized. Returning {@code null}
     * (rather than throwing) lets {@link #aggregateDay} skip the offending line and
     * keep the window intact — a forward-compatible outcome from a newer factory
     * version must never crash the dashboard render (FR6, NFR-R1 of add-dashboard-page).
     */
    private static @Nullable TaskOutcome parseOutcome(@Nullable String outcome) {
        if (outcome == null) {
            return null;
        }
        return switch (outcome) {
            case "delivered" -> TaskOutcome.DELIVERED;
            case "awaitingHuman" -> TaskOutcome.AWAITING_HUMAN;
            case "aborted" -> TaskOutcome.ABORTED;
            case "revoked" -> TaskOutcome.REVOKED;
            default -> null;
        };
    }
}
