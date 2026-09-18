package com.github.oinsio.gnomish.untrustedtext;

/**
 * The sink's bound on one whole rendered record, as distinct from {@link TextSafety#capTail},
 * which is the call site's bound on one excerpt. The two keep opposite ends for opposite reasons:
 * an excerpt of command output carries its error at the tail, while a log record carries its
 * timestamp, level, logger and operator-event code at the <b>head</b> — a record that lost those
 * is not findable at all.
 *
 * <p>Over-cap text is cut to the head and a visible marker naming the drop is appended, within the
 * same bound, so the result is never longer than the cap and a second pass finds nothing to do.
 *
 * <p>Extracted from {@link TextSafety}, which delegates, for the same reason
 * {@link LineFlattening} and {@link ConsoleNotation} are their own classes: the facade states what
 * the module offers, each class beside it owns one mechanism.
 *
 * <p>Implements FR2 of harden-untrusted-text-sinks.
 */
final class RecordCap {

    /**
     * Max characters one whole rendered record may occupy: 16 KB (design D2 of
     * harden-untrusted-text-sinks). Derivation — the log plane's widest output is one
     * {@link TextSafety#DEFAULT_CAP_CHARS}-character cap of six-character escapes, about 12 KB, so
     * a choke-point-prepared message can never reach this bound however many arguments a line
     * carries; and it is two orders of magnitude above the longest legitimate record the factory
     * emits (the per-task summary), while staying inside the async appender's queue budget.
     */
    static final int CAP_CHARS = 16_384;

    /**
     * Characters reserved inside {@link #CAP_CHARS} for the truncation marker, so the marked
     * result still fits the cap and a second pass is a no-op. Sized for the widest marker the
     * format can produce — both counts at {@code Integer.MAX_VALUE} — which
     * {@code TextSafetyRecordCapSpec} pins rather than leaving to arithmetic in a comment.
     */
    static final int MARKER_RESERVE = 64;

    private RecordCap() {}

    /**
     * Bounds {@code text} to {@link #CAP_CHARS}, keeping the head and marking the drop.
     *
     * @param text the rendered record to bound; never null
     * @return {@code text} unchanged when within the cap, else its marked head; never null
     */
    static String render(String text) {
        if (text.length() <= CAP_CHARS) {
            return text;
        }
        int headChars = CAP_CHARS - MARKER_RESERVE;
        // The head can end between the two halves of an astral character; a kept high half is an
        // unpaired surrogate every UTF-8 sink renders as a replacement character — evidence the
        // reader cannot tell from a real one.
        if (Character.isHighSurrogate(text.charAt(headChars - 1))) {
            headChars--;
        }
        return text.substring(0, headChars)
                + " [record truncated, dropped %d of %d chars]".formatted(text.length() - headChars, text.length());
    }
}
