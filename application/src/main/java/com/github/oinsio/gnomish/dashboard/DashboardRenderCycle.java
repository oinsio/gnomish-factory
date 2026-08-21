package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.serveobservability.ObservabilityPaths;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One dashboard render: reads the daemon snapshot and the ledger history window fresh, and
 * composes them with a caller-resolved board section into the page HTML (task 4.1-4.4). Shared by
 * the one-shot render ({@code DashboardCommand}) and each cycle of {@link DashboardWatchLoop} — in
 * both cases the snapshot and ledger are re-read every call (FR9: local files, effectively free),
 * while the board section is the caller's concern (a fresh {@link DashboardBoardCache} fetch for
 * one-shot; the cache's cadence-gated {@code refresh}/{@code cached} choice for watch mode).
 *
 * <p>An unreadable ledger window (a malformed non-tail line, {@link
 * LedgerAggregator#aggregate}'s checked failure) degrades to an empty history section rather than
 * failing the render — the same "a degraded section never fails the others" contract {@link
 * SnapshotReader} already gives the daemon section (FR3, design D9).
 *
 * <p>Implements FR3, FR6, FR9, NFR-O1, NFR-R1 of add-dashboard-page (design D9).
 */
public final class DashboardRenderCycle {

    private final SnapshotReader snapshotReader = new SnapshotReader();
    private final LedgerAggregator ledgerAggregator = new LedgerAggregator();
    private final SweepActionAggregator sweepActionAggregator = new SweepActionAggregator();
    private final DashboardHtmlRenderer htmlRenderer = new DashboardHtmlRenderer();

    /**
     * Renders the full page for one cycle.
     *
     * @param homeDir the user's home directory the observability files live under; never null
     * @param instanceName the configured instance name (design D2); never null
     * @param boardView the board section's already-resolved view; never null
     * @param now the page's observation instant, also used to resolve the snapshot/ledger reads;
     *     never null
     * @param renderCadence the {@code --watch} render cadence, or {@code null} for a one-shot
     *     render (FR7, FR8); passed straight through to {@link DashboardHtmlRenderer#render}
     * @return the self-contained HTML document
     */
    public String render(
            Path homeDir,
            String instanceName,
            BoardSectionView boardView,
            Instant now,
            @Nullable Duration renderCadence) {
        var daemonView = snapshotReader.read(ObservabilityPaths.snapshotFile(homeDir, instanceName), now);
        var historyView = readHistory(homeDir, instanceName, now);
        var hygieneView = readHygiene(daemonView, homeDir, instanceName, now);
        return htmlRenderer.render(daemonView, historyView, boardView, hygieneView, now, renderCadence);
    }

    /**
     * NFR-O3 of add-serve-sandbox-lifecycle: the hygiene section composes the snapshot's sweep
     * vital with the ledger's sweep actions, and degrades on each half independently — an
     * unreadable ledger leaves the vital's breakdown intact, and a snapshot from a build without
     * the vital still shows the ledger's actions.
     */
    private SandboxHygieneView readHygiene(
            DaemonSnapshotView daemonView, Path homeDir, String instanceName, Instant now) {
        var sweep = SweepVitalReader.read(daemonView);
        SweepActionWindow actions;
        try {
            actions = sweepActionAggregator.aggregate(
                    homeDir,
                    instanceName,
                    LocalDate.ofInstant(now, ZoneOffset.UTC),
                    LedgerAggregator.DEFAULT_WINDOW_DAYS);
        } catch (IOException malformedLedger) {
            actions = SweepActionWindow.EMPTY;
        }
        return new SandboxHygieneView(sweep, actions.rows(), actions.total());
    }

    private LedgerHistoryView readHistory(Path homeDir, String instanceName, Instant now) {
        try {
            return ledgerAggregator.aggregate(homeDir, instanceName, LocalDate.ofInstant(now, ZoneOffset.UTC));
        } catch (IOException malformedLedger) {
            return new LedgerHistoryView(List.of(), Map.of());
        }
    }
}
