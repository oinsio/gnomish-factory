package com.github.oinsio.gnomish.dashboard;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Renders the dashboard page as one self-contained HTML string (task 3.1):
 * three independently degrading sections — daemon, history, board — each
 * carrying its own data timestamp, plus the page's own {@code generatedAt}
 * (FR2, FR3, FR10, NFR-O1). A string-template sibling of {@link
 * com.github.oinsio.gnomish.app.BoardTextRenderer} / {@code
 * StatusTextRenderer}: plain Java text-building with escaped interpolation,
 * no template engine (ADR 0001, design D6). Static CSS lives as a constant
 * so the page never issues an external request (FR2); each section's markup
 * is delegated to its own package-private renderer, and shared
 * escaping/label formatting to {@link DashboardHtmlFormatter}, to keep every
 * file within the project's file-size guidance. The watch-mode staleness
 * banner (task 3.3) is delegated to {@link DashboardStalenessBannerRenderer}
 * and only appended when a render cadence is supplied; the same cadence bakes
 * a matching {@code <meta http-equiv="refresh">} into the head so a {@code
 * file://} tab reloads itself (FR7), while driving the watch loop remains
 * {@link DashboardWatchLoop}'s job (task group 4).
 * The daemon section's alert-condition highlight (task 3.4) is delegated to
 * {@link DashboardDaemonSectionRenderer}, which is handed this same {@code
 * generatedAt} as its evaluation instant — layer 1 of design D3's two
 * independent staleness layers, distinct from this class's own layer-2 page
 * banner.
 *
 * <p>Implements FR2, FR3, FR4, FR7, FR8, FR10, UX3, NFR-O1 of add-dashboard-page (design D3, D6).
 */
public final class DashboardHtmlRenderer {

    private static final String STYLE = """
            body { font-family: system-ui, sans-serif; margin: 1rem; color: #111; }
            section { margin-bottom: 1.5rem; }
            h2 { margin-bottom: 0.25rem; }
            .timestamp { color: #555; font-size: 0.85em; }
            table { border-collapse: collapse; width: 100%; }
            td, th { border: 1px solid #ccc; padding: 0.2rem 0.5rem; text-align: left; font-size: 0.9em; }
            .bar { display: inline-block; height: 0.6em; background: #888; }
            #staleness-banner { display: none; position: fixed; inset: 0; padding: 1rem;
              background: #b00020; color: #fff; font-weight: bold; text-align: center; z-index: 1000;
              align-items: center; justify-content: center; }
            #staleness-banner.stale { display: flex; }
            .daemon-alert { background: #fff3e0; border: 2px solid #e65100; padding: 0.5rem; }
            .daemon-alert-text { color: #e65100; font-weight: bold; }
            """;

    private final DashboardDaemonSectionRenderer daemonSection = new DashboardDaemonSectionRenderer();
    private final DashboardHistorySectionRenderer historySection = new DashboardHistorySectionRenderer();
    private final DashboardBoardSectionRenderer boardSection = new DashboardBoardSectionRenderer();

    /**
     * Renders the full page from the three section view models, the page's
     * observation instant, and its watch-mode render cadence, if any.
     *
     * @param daemonView the daemon section's data; never null
     * @param historyView the history section's data; never null
     * @param boardView the board section's data; never null
     * @param generatedAt the page's own observation instant; never null
     * @param renderCadence the {@code --watch} render cadence, or {@code
     *     null} for a one-shot render — presence is what selects watch mode
     *     and bakes the layer-2 staleness banner script (FR7, FR8, design D4)
     * @return the self-contained HTML document
     */
    public String render(
            DaemonSnapshotView daemonView,
            LedgerHistoryView historyView,
            BoardSectionView boardView,
            Instant generatedAt,
            @Nullable Duration renderCadence) {
        Objects.requireNonNull(daemonView, "daemonView");
        Objects.requireNonNull(historyView, "historyView");
        Objects.requireNonNull(boardView, "boardView");
        Objects.requireNonNull(generatedAt, "generatedAt");

        StringBuilder out = new StringBuilder();
        out.append("<!doctype html>\n<html><head><meta charset=\"utf-8\"><title>gnomish dashboard</title>");
        if (renderCadence != null) {
            out.append("<meta http-equiv=\"refresh\" content=\"")
                    .append(renderCadence.toSeconds())
                    .append("\">");
        }
        out.append("<style>").append(STYLE).append("</style></head><body>\n");
        if (renderCadence != null) {
            DashboardStalenessBannerRenderer.append(out, generatedAt, renderCadence);
        }
        out.append("<p class=\"timestamp\">generated at ")
                .append(DashboardHtmlFormatter.escape(generatedAt.toString()))
                .append("</p>\n");
        daemonSection.append(out, daemonView, generatedAt);
        historySection.append(out, historyView);
        boardSection.append(out, boardView);
        out.append("</body></html>\n");
        return out.toString();
    }
}
