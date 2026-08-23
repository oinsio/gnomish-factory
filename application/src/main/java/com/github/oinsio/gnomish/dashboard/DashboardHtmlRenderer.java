package com.github.oinsio.gnomish.dashboard;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Renders the dashboard page as one self-contained HTML string: four
 * priority layers in fixed order — freshness strip, status line,
 * waiting-for-a-human, in-progress — followed by the quieter reference
 * blocks (outcomes by day, tokens, sandbox hygiene). The order is the
 * page's whole argument: an operator answers "is anything waiting for me?"
 * from the top two layers without scrolling, and no later layer is allowed
 * to be visually louder than an earlier one (FR1). Blocks never appear or
 * disappear with data — each one renders its own explicit empty-state
 * sentence in place (FR2).
 *
 * <p>The stylesheet and the client script are classpath resources read once
 * at construction and inlined into every render, so the output stays a
 * single file that renders fully styled over {@code file://} with no
 * network (FR10, NFR-R1, design D1).
 *
 * <p>Staleness degrades the page and never covers it (FR3, design D3): the
 * old full-viewport banner is gone, and in its place a persistent freshness
 * strip sits above every block. Everything it needs travels as data
 * attributes on {@code <body>} — {@code data-generated-at} and {@code
 * data-mode} — so the script stays a static resource with nothing templated
 * inside it, and the watch/one-shot split rides the same channel: a watch
 * page degrades when it goes stale and carries a meta-refresh, a one-shot
 * page shows its age as plain information and carries neither.
 *
 * <p>Implements FR1, FR2, FR3, FR8, FR9, FR10, NFR-R1, NFR-R2 of redesign-dashboard
 * (design D1, D3, D4, D6).
 */
public final class DashboardHtmlRenderer {

    private final DashboardResources resources;

    private final DashboardStatusCardRenderer statusCard = new DashboardStatusCardRenderer();
    private final DashboardAttentionCardRenderer attentionCard = new DashboardAttentionCardRenderer();
    private final DashboardInProgressCardRenderer inProgressCard = new DashboardInProgressCardRenderer();
    private final DashboardOutcomesCardRenderer outcomesCard = new DashboardOutcomesCardRenderer();
    private final DashboardTokensCardRenderer tokensCard = new DashboardTokensCardRenderer();
    private final DashboardHygieneBlockRenderer hygieneBlock = new DashboardHygieneBlockRenderer();

    /**
     * Reads the page's stylesheet and client script from the classpath.
     *
     * @throws IllegalStateException when either resource is missing or would
     *     terminate the inline {@code <script>} block early (NFR-R1)
     */
    public DashboardHtmlRenderer() {
        this.resources = DashboardResources.load();
    }

    /**
     * Renders the full page from the four block view models, the page's
     * observation instant, and its watch-mode render cadence, if any.
     *
     * @param daemonView the status line's data; never null
     * @param historyView the outcomes and tokens blocks' data; never null
     * @param boardView the waiting-for-a-human and in-progress blocks' data; never null
     * @param hygieneView the sandbox hygiene block's data, {@link SandboxHygieneView#absent()}
     *     when neither the snapshot nor the ledger carries sweep data; never null
     * @param generatedAt the page's own observation instant; never null
     * @param renderCadence the {@code --watch} render cadence, or {@code null} for a one-shot
     *     render — presence is what selects watch mode, baking the meta-refresh and arming the
     *     script's stale degradation (FR3, FR10, design D3); at least one second, since a
     *     shorter cadence truncates to {@code content="0"} in the meta-refresh
     * @return the self-contained HTML document
     * @throws IllegalArgumentException when {@code renderCadence} is shorter than one second
     */
    public String render(
            DaemonSnapshotView daemonView,
            LedgerHistoryView historyView,
            BoardSectionView boardView,
            SandboxHygieneView hygieneView,
            Instant generatedAt,
            @Nullable Duration renderCadence) {
        Objects.requireNonNull(daemonView, "daemonView");
        Objects.requireNonNull(historyView, "historyView");
        Objects.requireNonNull(boardView, "boardView");
        Objects.requireNonNull(hygieneView, "hygieneView");
        Objects.requireNonNull(generatedAt, "generatedAt");
        requireUsableCadence(renderCadence);

        StringBuilder out = new StringBuilder();
        appendHead(out, renderCadence);
        appendBodyOpen(out, generatedAt, renderCadence);
        appendFreshnessStrip(out, generatedAt);
        statusCard.append(out, daemonView, hygieneView, generatedAt);
        attentionCard.append(out, boardView);
        inProgressCard.append(out, boardView);
        outcomesCard.append(out, historyView);
        tokensCard.append(out, historyView);
        hygieneBlock.append(out, hygieneView);
        out.append("</div>\n<script>").append(resources.js()).append("</script>\n</body></html>\n");
        return out.toString();
    }

    /**
     * The meta-refresh interval is written in whole seconds, so any cadence
     * under a second truncates to {@code content="0"} — a browser reload storm
     * rather than a refresh. The page is never rendered with one; one second is
     * the shortest usable value, and the shipped watch loop uses ten (FR10).
     */
    private static void requireUsableCadence(@Nullable Duration renderCadence) {
        if (renderCadence != null && renderCadence.toSeconds() < 1L) {
            throw new IllegalArgumentException("renderCadence must be at least one second, but was " + renderCadence);
        }
    }

    private void appendHead(StringBuilder out, @Nullable Duration renderCadence) {
        out.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>gnomish factory</title>\n");
        if (renderCadence != null) {
            out.append("<meta http-equiv=\"refresh\" content=\"")
                    .append(renderCadence.toSeconds())
                    .append("\">\n");
        }
        out.append("<style>").append(resources.css()).append("</style>\n</head>\n");
    }

    /**
     * A watch page also bakes its stale threshold — three times the render
     * cadence — as {@code data-stale-after}, so the script degrades at the
     * cadence the page was actually rendered with instead of assuming the
     * shipped ten seconds (FR3, design D3).
     */
    private static void appendBodyOpen(StringBuilder out, Instant generatedAt, @Nullable Duration renderCadence) {
        out.append("<body data-mode=\"")
                .append(renderCadence == null ? "oneshot" : "watch")
                .append("\" data-generated-at=\"")
                .append(generatedAt.toEpochMilli())
                .append('"');
        if (renderCadence != null) {
            out.append(" data-stale-after=\"")
                    .append(renderCadence.toMillis() * 3L)
                    .append('"');
        }
        out.append(">\n<div class=\"wrap\">\n");
    }

    /**
     * The strip is server-rendered fresh with the page's absolute
     * {@code generatedAt}, written through {@link DashboardTime} like every
     * other timestamp on the page (FR8): that {@code <time>} element is what a
     * reader without scripting sees, and the script lifts its ISO text onto the
     * strip's {@code title} before replacing the sentence with a ticking age
     * once per second (NFR-R2, design D4).
     */
    private static void appendFreshnessStrip(StringBuilder out, Instant generatedAt) {
        out.append("<div class=\"freshness\" id=\"freshness\" data-state=\"fresh\" role=\"status\" ")
                .append("aria-live=\"polite\">")
                .append(DashboardIcons.FRESHNESS_FRESH)
                .append(DashboardIcons.FRESHNESS_STALE)
                .append("<span id=\"freshness-text\">updated ");
        DashboardTime.append(out, generatedAt, null);
        out.append("</span></div>\n");
    }
}
