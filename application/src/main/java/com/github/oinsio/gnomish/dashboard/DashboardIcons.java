package com.github.oinsio.gnomish.dashboard;

import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import org.jspecify.annotations.Nullable;

/**
 * The page's inline SVG glyph set. Every icon is a hand-written path in a
 * {@code 0 0 16 16} box drawn in {@code currentColor}, so it inherits the
 * design-token colour of whatever it sits in and needs no colour literal of
 * its own (NFR-O1) — and, being inline, no external asset, sprite sheet, or
 * icon font (NG4, FR10's single-file contract).
 *
 * <p>Icons are decorative here: each one sits beside text that already says
 * the same thing, so they carry {@code aria-hidden} rather than a label a
 * screen reader would read twice.
 *
 * <p>Implements FR3, FR4, FR10, NFR-O1 of redesign-dashboard.
 */
final class DashboardIcons {

    private static final String STROKE = "fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\"";

    /** The fresh half of the freshness strip: a refresh arrow. CSS hides it when the strip goes stale. */
    static final String FRESHNESS_FRESH = svg(
            "freshness__icon freshness__icon--fresh",
            "<path d=\"M13 8a5 5 0 1 1-1.7-3.8\" " + STROKE + " stroke-linecap=\"round\"/>"
                    + "<path d=\"M13 2.6V6H9.6\" " + STROKE + " stroke-linecap=\"round\" stroke-linejoin=\"round\"/>");

    /** The stale half of the freshness strip: a warning triangle. CSS reveals it in the stale state. */
    static final String FRESHNESS_STALE = svg(
            "freshness__icon freshness__icon--stale",
            "<path d=\"M8 2.2 14.2 13.2H1.8Z\" " + STROKE + " stroke-linejoin=\"round\"/>"
                    + "<path d=\"M8 6.4v3.1\" " + STROKE + " stroke-linecap=\"round\"/>"
                    + "<circle cx=\"8\" cy=\"11.4\" r=\".85\" fill=\"currentColor\"/>");

    /** The all-clear glyph for a calm empty state (UX1). */
    static final String CHECK = svg(
            null,
            "<path d=\"M3.2 8.4 6.5 11.7 12.8 4.9\" " + STROKE
                    + " stroke-linecap=\"round\" stroke-linejoin=\"round\"/>");

    /** The neutral glyph for a factual empty state — nothing is wrong, there is simply nothing here. */
    static final String DASH = svg(null, "<path d=\"M4 8h8\" " + STROKE + " stroke-linecap=\"round\"/>");

    private DashboardIcons() {}

    /**
     * Returns the glyph distinguishing why a task is parked, so the reason
     * category reads before the row's text does (FR4).
     *
     * @param reason the row's park reason; never null
     * @return the inline SVG for that category
     */
    static String parkReason(ParkReason reason) {
        return switch (reason) {
            case ESCALATION ->
                svg(
                        "row__icon",
                        "<circle cx=\"8\" cy=\"8\" r=\"6\" " + STROKE + "/>"
                                + "<path d=\"M8 4.8v3.9\" " + STROKE + " stroke-linecap=\"round\"/>"
                                + "<circle cx=\"8\" cy=\"11.2\" r=\".85\" fill=\"currentColor\"/>");
            case CHECKPOINT ->
                svg(
                        "row__icon",
                        "<rect x=\"4.4\" y=\"3.4\" width=\"2.3\" height=\"9.2\" rx=\".8\" fill=\"currentColor\"/>"
                                + "<rect x=\"9.3\" y=\"3.4\" width=\"2.3\" height=\"9.2\" rx=\".8\" fill=\"currentColor\"/>");
            case INFRA ->
                svg("row__icon", "<path d=\"M9.2 1.8 3.6 9.1H7l-.6 5.1 5.9-7.4H8.7Z\" fill=\"currentColor\"/>");
        };
    }

    private static String svg(@Nullable String cssClass, String body) {
        return "<svg" + (cssClass == null ? "" : " class=\"" + cssClass + "\"")
                + " viewBox=\"0 0 16 16\" aria-hidden=\"true\">" + body + "</svg>";
    }
}
