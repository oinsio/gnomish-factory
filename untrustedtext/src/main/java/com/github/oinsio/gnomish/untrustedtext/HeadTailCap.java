package com.github.oinsio.gnomish.untrustedtext;

/**
 * The head-and-tail truncation the carrier bounds itself by: the two ends a reader opens a long
 * capture for, joined by a marker naming exactly how many characters were dropped. For a rendered
 * exception chain the head carries the top-level message and throw site, the tail the deepest
 * {@code Caused by:}. Never a silent cut, and never a cut that drops the end.
 *
 * <p>Distinct from {@link LineFlattening}'s tail-only cap, which bounds a log line: that one keeps
 * the end because a log record is read for the failure, while a tracker comment is read for both
 * ends. Different boundary, different invariant — which is why the two live side by side rather
 * than one calling the other.
 *
 * <p>Introduced by cap-abort-cause-length as {@code AbortCauseBudget}'s own logic and moved here by
 * type-untrusted-text (design D13): a transformation that keeps the text inside the carrier is not
 * a way out, so it belongs to the carrier and needs no exit annotation.
 *
 * <p>Implements FR10 of type-untrusted-text.
 */
final class HeadTailCap {

    /** Fraction of the kept characters given to the head; the tail takes the rest. */
    private static final int HEAD_NUMERATOR = 2;

    private static final int HEAD_DENOMINATOR = 3;

    /**
     * How far either cut may travel to land on a line boundary. Readability only — the length bound
     * and the marker are what correctness rests on — so the window is small enough that a text
     * without line breaks nearby simply cuts where it was going to.
     */
    private static final int LINE_SNAP_WINDOW = 200;

    private HeadTailCap() {}

    /**
     * Bounds {@code text} to {@code cap} characters.
     *
     * @param text the raw text; never null
     * @param cap the maximum characters of the result; at least {@link UntrustedText#MIN_CAP_CHARS}
     * @return {@code text} itself when within the bound, else its head and tail joined by the
     *     omission marker
     */
    static String cap(String text, int cap) {
        if (text.length() <= cap) {
            return text;
        }
        // The marker's own length depends on the omitted count, which depends on how much the
        // marker leaves for the halves. Sizing the reservation from the whole text's length
        // breaks the circle: the omitted count is always smaller, so it never needs more digits,
        // and the finished text lands at or (by at most a digit) under the bound.
        int kept = cap - marker(text.length()).length();
        String head = head(text, kept * HEAD_NUMERATOR / HEAD_DENOMINATOR);
        String tail = tail(text, kept - head.length());
        return head + marker(text.length() - head.length() - tail.length()) + tail;
    }

    /** The kept head, cut back to a nearby line boundary when one is in reach. */
    private static String head(String text, int length) {
        int boundary = text.lastIndexOf('\n', length);
        int cut = boundary >= length - LINE_SNAP_WINDOW ? boundary : withoutSplitPair(text, length);
        return text.substring(0, cut);
    }

    /**
     * The kept tail, advanced to start after a nearby line boundary when one is in reach. The
     * search runs backwards from the far end of the window, so the boundary it finds is the last
     * one inside it — the least text given up for the snap — and a hit before {@code start} means
     * the window held none at all.
     */
    private static String tail(String text, int length) {
        int start = text.length() - length;
        int boundary = text.lastIndexOf('\n', start + LINE_SNAP_WINDOW);
        int cut = boundary >= start ? boundary + 1 : afterSplitPair(text, start);
        return text.substring(cut);
    }

    /**
     * Pulls a cut back off the low half of an astral character. The bound counts UTF-16 units, so a
     * boundary can land inside a surrogate pair; keeping one half emits an unpaired surrogate,
     * which every UTF-8 sink downstream renders as a replacement character the reader cannot tell
     * from a real one.
     */
    private static int withoutSplitPair(String text, int index) {
        return Character.isLowSurrogate(text.charAt(index)) ? index - 1 : index;
    }

    /** The same guard from the other side: a tail never opens on an orphaned low surrogate. */
    private static int afterSplitPair(String text, int index) {
        return Character.isLowSurrogate(text.charAt(index)) ? index + 1 : index;
    }

    /** The omission marker, on its own line between the two halves. */
    private static String marker(int omitted) {
        return "\n… [" + omitted + " characters omitted] …\n";
    }
}
