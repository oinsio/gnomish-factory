package com.github.oinsio.gnomish.adapter.findings;

import java.util.regex.Pattern;

/**
 * The sanitization half of the unified findings funnel (design D9): environment-derived
 * findings text passes through here before any log line, so ANSI/terminal escape sequences
 * and control characters are stripped once, in one tested place, and log volume is bounded
 * by a tail cap noting truncation. Findings as <em>data</em> are deliberately untouched —
 * {@code state.json} carries them in full — sanitization applies at the sinks: log lines
 * through {@link #forLog}, tracker publication through {@link TrackerFence}.
 *
 * <p>Stripping removes ANSI CSI/OSC/Fe sequences and every ISO control character except
 * {@code \n} and {@code \t} (including DEL and the C1 range), neutralizing
 * terminal-escape attacks on the operator's console and log processors while keeping the
 * text's line structure readable.
 *
 * <p>Implements FR15, NFR-C1 of add-sandbox-core.
 */
public final class FindingsSanitizer {

    /**
     * Max characters {@link #forLog} keeps from the tail of one text: enough for a stack
     * trace or assertion failure, small enough that a hostile multi-megabyte output cannot
     * flood the logs (NFR-C1).
     */
    static final int LOG_TAIL_CAP_CHARS = 2_000;

    /**
     * ANSI escape sequences: CSI ({@code ESC [ params intermediates final}), OSC
     * ({@code ESC ] ... BEL} or {@code ESC ] ... ST}), and single-character Fe escapes.
     * Any ESC the pattern does not match is removed by the control-character filter.
     */
    private static final Pattern ANSI = Pattern.compile(
            "\\u001B(?:\\[[0-9;?]*[ -/]*[@-~]|\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)?|[@-Z\\\\-_])");

    private FindingsSanitizer() {}

    /**
     * Strips ANSI escape sequences and control characters (keeping {@code \n} and
     * {@code \t}) from {@code text} without truncating it (FR15).
     *
     * @param text the raw environment-derived text; never null
     * @return the stripped text; never null
     */
    public static String strip(String text) {
        String noAnsi = ANSI.matcher(text).replaceAll("");
        StringBuilder out = new StringBuilder(noAnsi.length());
        for (int i = 0; i < noAnsi.length(); i++) {
            char c = noAnsi.charAt(i);
            if (!isStrippedControl(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Prepares {@code text} for a log line: {@link #strip} plus a {@value
     * #LOG_TAIL_CAP_CHARS}-character tail cap noting truncation (FR15, NFR-C1).
     *
     * @param text the raw environment-derived text; never null
     * @return the sanitized, bounded text; never null
     */
    public static String forLog(String text) {
        return capTail(strip(text), LOG_TAIL_CAP_CHARS);
    }

    /**
     * Keeps only the last {@code cap} characters of {@code text}, prepending a marker
     * naming what was dropped — the tail carries the error in typical build output, so
     * capping keeps the signal while bounding hostile volume (NFR-C1).
     *
     * @param text the text to bound; never null
     * @param cap the maximum characters to keep; positive
     * @return {@code text} unchanged when within the cap, else its marked tail; never null
     */
    public static String capTail(String text, int cap) {
        if (cap <= 0) {
            throw new IllegalArgumentException("cap must be positive, got " + cap);
        }
        if (text.length() <= cap) {
            return text;
        }
        String tail = text.substring(text.length() - cap);
        return "[truncated, showing last %d of %d chars]\n%s".formatted(cap, text.length(), tail);
    }

    /**
     * A character {@link #strip} removes: ISO controls except {@code \n} and {@code \t},
     * DEL, and the C1 range — the carriers of cursor tricks and log forgery.
     */
    private static boolean isStrippedControl(char c) {
        if (c == '\n' || c == '\t') {
            return false;
        }
        return c < 0x20 || (c >= 0x7F && c <= 0x9F);
    }
}
