package com.github.oinsio.gnomish.dashboard;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;

/**
 * Writes one timestamp into the page as the {@code <time>} element the
 * whole staleness and age presentation is built on (FR8): the full ISO
 * instant in {@code datetime}, the untruncated epoch millis in {@code
 * data-epoch}, and the absolute instant as the element's text.
 *
 * <p>The split of work is design D4's: the server renders the value in
 * final, complete form, and the client script only re-presents it — it
 * rewrites the text to a short relative age each second and moves the
 * absolute value into {@code title}. With scripting disabled the absolute
 * text simply stays, which is why it must be the exact instant and not a
 * pre-relativized phrase (NFR-R2, UX2).
 *
 * <p>Text and {@code datetime} are truncated to the second so the page
 * stays scannable; {@code data-epoch} keeps the full precision, since it is
 * arithmetic input rather than something a human reads.
 *
 * <p>Implements FR8, NFR-R2, UX2 of redesign-dashboard (design D4).
 */
final class DashboardTime {

    private DashboardTime() {}

    /**
     * Appends a {@code <time>} element for {@code at}.
     *
     * @param out the page buffer being built; never null
     * @param at the instant to render; never null
     * @param cssClass classes for the element, or {@code null} for none
     */
    static void append(StringBuilder out, Instant at, @Nullable String cssClass) {
        String absolute = at.truncatedTo(ChronoUnit.SECONDS).toString();
        out.append("<time");
        if (cssClass != null) {
            out.append(" class=\"")
                    .append(DashboardHtmlFormatter.escape(cssClass))
                    .append('"');
        }
        out.append(" datetime=\"")
                .append(absolute)
                .append("\" data-epoch=\"")
                .append(at.toEpochMilli())
                .append("\">")
                .append(absolute)
                .append("</time>");
    }
}
