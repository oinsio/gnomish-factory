package com.github.oinsio.gnomish.dashboard;

import java.time.Duration;
import java.time.Instant;

/**
 * Renders the watch-mode page-staleness banner (task 3.3): bakes the page's
 * {@code generatedAt} (as epoch millis) and its refresh-cadence-derived
 * staleness threshold ({@code k = STALENESS_MULTIPLIER} times the render
 * cadence, design D4) into an inline script that compares the baked instant
 * against the browser clock on load, plus the initially-hidden banner
 * element it toggles visible. This is layer 2 of the two-layer staleness
 * model (design D3) — a dead *renderer*, not a dead daemon (that is {@link
 * AlertConditionEvaluator}'s job, layer 1). Meta-refresh (task 4.4) plays no
 * part in this detection: it would keep "reloading" a file a dead renderer
 * no longer updates, which is exactly why the browser's own clock, read at
 * load time, is what catches it.
 *
 * <p>One-shot pages (no {@code --watch}) have no cadence and never call
 * this renderer — {@link DashboardHtmlRenderer} shows their {@code
 * generatedAt} as plain, non-bannering text instead (FR8).
 *
 * <p>Implements FR8, M3 of add-dashboard-page (design D3, D4).
 */
final class DashboardStalenessBannerRenderer {

    /** Page-staleness multiple {@code k} (design D4): banner past k x the render cadence. */
    static final int STALENESS_MULTIPLIER = 3;

    private DashboardStalenessBannerRenderer() {}

    /**
     * Appends the banner element and its inline detection script.
     *
     * @param out the page buffer being built; never null
     * @param generatedAt the page's observation instant; never null
     * @param renderCadence the watch-mode render cadence; never null
     */
    static void append(StringBuilder out, Instant generatedAt, Duration renderCadence) {
        long thresholdMillis = renderCadence.toMillis() * STALENESS_MULTIPLIER;
        out.append("<div id=\"staleness-banner\">view is stale &mdash; the renderer may have stopped</div>\n");
        out.append("<script>\n(function () {\n")
                .append("  var generatedAtMillis = ")
                .append(generatedAt.toEpochMilli())
                .append(";\n  var thresholdMillis = ")
                .append(thresholdMillis)
                .append(";\n  if (Date.now() - generatedAtMillis > thresholdMillis) {\n")
                .append("    document.getElementById('staleness-banner').classList.add('stale');\n")
                .append("  }\n})();\n</script>\n");
    }
}
