package com.github.oinsio.gnomish.dashboard;

/**
 * The stacked bar both the outcomes block and the tokens block draw: the
 * segments themselves and the sentence a screen reader is given in their
 * place. The two blocks measure different things over different palettes,
 * but a bar is a bar — apportion the values, skip anything that came out at
 * zero width, and name every raw value in the {@code aria-label} so the
 * chart is never the only place a number exists (NFR-R2).
 *
 * <p>Each caller keeps its own token and label tables: the palettes are
 * deliberately different — outcome status colours against the neutral spend
 * palette (FR6, FR7) — and that difference is the thing a shared table would
 * quietly erase.
 *
 * <p>Zero-width segments are omitted rather than emitted at {@code width:0}:
 * {@link DashboardPercentage#shares} already guarantees a positive value
 * gets at least one point, so a segment reaching zero here really is an
 * absent category.
 *
 * <p>Implements FR6, FR7, NFR-R2 of redesign-dashboard (design D5).
 */
final class DashboardBar {

    private DashboardBar() {}

    /**
     * Appends one bar's segments, apportioned across {@code values}.
     *
     * @param out the page buffer; never null
     * @param values each segment's raw count, in render order; never null
     * @param tokens the CSS custom property backing each segment, in the same order; never null
     */
    static void appendSegments(StringBuilder out, long[] values, String[] tokens) {
        int[] shares = DashboardPercentage.shares(values);
        for (int i = 0; i < values.length; i++) {
            appendSegment(out, shares[i], tokens[i]);
        }
    }

    /**
     * The bar's spoken equivalent: every raw count with the category it belongs to.
     *
     * @param values each segment's raw count, in render order; never null
     * @param labels the spoken name of each segment, in the same order; never null
     * @return the {@code aria-label} text, unescaped
     */
    static String ariaLabel(long[] values, String[] labels) {
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                label.append(", ");
            }
            label.append(values[i]).append(' ').append(labels[i]);
        }
        return label.toString();
    }

    private static void appendSegment(StringBuilder out, int percent, String token) {
        if (percent == 0) {
            return;
        }
        out.append("<span class=\"bar__seg\" style=\"width:")
                .append(percent)
                .append("%;background:var(")
                .append(token)
                .append(")\"></span>");
    }
}
