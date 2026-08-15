package com.github.oinsio.gnomish.dashboard;

/**
 * Package-private HTML-escaping helper for {@link DashboardHtmlRenderer}:
 * split out to keep both files within the project's file-size guidance.
 * {@link
 * #escape} is the one place every piece of composed-surface text (titles,
 * ids, failure messages) passes through before reaching the page, since the
 * page's content ultimately comes from tracker/ledger/snapshot data the
 * renderer does not otherwise sanitize (NFR-S1). The Ready-row eligibility
 * annotation and AwaitingHuman-row park-reason label live in {@link
 * com.github.oinsio.gnomish.board.BoardLabels}, shared with the board's text
 * renderer.
 *
 * <p>Implements FR2, NFR-S1 of add-dashboard-page (design D6).
 */
final class DashboardHtmlFormatter {

    private DashboardHtmlFormatter() {}

    /**
     * Escapes {@code text} for safe inclusion in HTML element content or
     * attribute values: {@code &}, {@code <}, {@code >}, {@code "}, and
     * {@code '} are replaced with their entity references.
     *
     * @param text the raw text; never null
     * @return the escaped text, safe to concatenate directly into markup
     */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
