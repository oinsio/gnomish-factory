package com.github.oinsio.gnomish.app.take;

/**
 * The bound every abort-cause text passes before it reaches a tracker write
 * (design D1-D4 of cap-abort-cause-length). The dominant cause producer renders
 * a full exception chain, and a tracker rejects a comment body over its own
 * limit — silently, because both abort writes are best-effort, which is how an
 * oversized cause loses the abort marker and corrupts the consecutive-abort
 * accounting behind the K fuse.
 *
 * <p>Truncation keeps the head and the tail, joined by a marker naming exactly
 * how many characters were dropped: for a rendered exception the head carries
 * the top-level message and throw site, the tail the deepest {@code Caused by:}
 * — the two ends an operator opens the report for. Never a silent cut, and
 * never a cut that drops the end.
 *
 * <p>This is not the {@code LogText}/{@code FindingsSanitizer} rule at a third
 * site: those cap log lines and plugin findings tail-only, this caps tracker
 * comment bodies head+tail. Different boundary, different invariant.
 *
 * <p>Implements FR1, NFR-O1, UX1 of cap-abort-cause-length.
 */
final class AbortCauseBudget {

    /**
     * Max characters of abort cause any tracker write may carry. Derived from the smallest
     * comment limit among supported and planned trackers — Jira Cloud's 32,767 characters
     * (GitHub's is 65,536) — less headroom for the fuse-trip report's own framing (counts,
     * timestamps, guidance: well under 1,000 characters today) and the abort marker's fixed
     * prefix. Not configurable: it guards a hard API limit, not a preference (design D2).
     */
    static final int BUDGET_CHARS = 28_000;

    /** Fraction of the kept characters given to the head; the tail takes the rest (design D3). */
    private static final int HEAD_NUMERATOR = 2;

    private static final int HEAD_DENOMINATOR = 3;

    /**
     * How far either cut may travel to land on a line boundary. Readability only — the length
     * bound and the marker are what correctness rests on — so the window is small enough that a
     * text without line breaks nearby simply cuts where it was going to.
     */
    private static final int LINE_SNAP_WINDOW = 200;

    private AbortCauseBudget() {}

    /**
     * Bounds {@code cause} to {@link #BUDGET_CHARS} characters.
     *
     * @param cause the raw cause text; never null
     * @return {@code cause} itself when within the budget, else its head and tail joined by the
     *     omission marker, never longer than the budget
     */
    static String cap(String cause) {
        if (cause.length() <= BUDGET_CHARS) {
            return cause;
        }
        // The marker's own length depends on the omitted count, which depends on how much the
        // marker leaves for the halves. Sizing the reservation from the whole text's length
        // breaks the circle: the omitted count is always smaller, so it never needs more digits,
        // and the finished text lands at or (by at most a digit) under the budget.
        int kept = BUDGET_CHARS - marker(cause.length()).length();
        String head = head(cause, kept * HEAD_NUMERATOR / HEAD_DENOMINATOR);
        String tail = tail(cause, kept - head.length());
        return head + marker(cause.length() - head.length() - tail.length()) + tail;
    }

    /** The kept head, cut back to a nearby line boundary when one is in reach. */
    private static String head(String cause, int length) {
        int boundary = cause.lastIndexOf('\n', length);
        int cut = boundary >= length - LINE_SNAP_WINDOW ? boundary : withoutSplitPair(cause, length);
        return cause.substring(0, cut);
    }

    /**
     * The kept tail, advanced to start after a nearby line boundary when one is in reach. The
     * search runs backwards from the far end of the window, so the boundary it finds is the last
     * one inside it — the least text given up for the snap — and a hit before {@code start} means
     * the window held none at all.
     */
    private static String tail(String cause, int length) {
        int start = cause.length() - length;
        int boundary = cause.lastIndexOf('\n', start + LINE_SNAP_WINDOW);
        int cut = boundary >= start ? boundary + 1 : afterSplitPair(cause, start);
        return cause.substring(cut);
    }

    /**
     * Pulls a cut back off the low half of an astral character. The budget counts UTF-16 units, so
     * a boundary can land inside a surrogate pair; keeping one half emits an unpaired surrogate,
     * which every UTF-8 sink downstream renders as a replacement character the reader cannot tell
     * from a real one.
     */
    private static int withoutSplitPair(String cause, int index) {
        return Character.isLowSurrogate(cause.charAt(index)) ? index - 1 : index;
    }

    /** The same guard from the other side: a tail never opens on an orphaned low surrogate. */
    private static int afterSplitPair(String cause, int index) {
        return Character.isLowSurrogate(cause.charAt(index)) ? index + 1 : index;
    }

    /** The omission marker, on its own line between the two halves (UX1). */
    private static String marker(int omitted) {
        return "\n… [" + omitted + " characters omitted] …\n";
    }
}
