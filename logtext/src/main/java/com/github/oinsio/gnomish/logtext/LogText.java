package com.github.oinsio.gnomish.logtext;

import java.util.regex.Pattern;

/**
 * The choke point untrusted text passes before it becomes part of a log line (FR6 of
 * harden-logging-observability): agent/LLM output, subprocess stderr, tracker-sourced strings and
 * in-container command output are all attacker-influenced, and a log line is a record other people
 * read as evidence. Three neutralizations, in this order:
 *
 * <ol>
 *   <li>{@link #strip} removes ANSI CSI/OSC/Fe sequences and every ISO control character except
 *       {@code \n} and {@code \t} (DEL and the C1 range included) — the carriers of cursor tricks
 *       against the operator's terminal;
 *   <li>{@link #capTail} bounds the volume, keeping the tail (where the error is) and naming what
 *       was dropped;
 *   <li>{@link #flatten} renders the surviving line separators — {@code \n}, {@code \r},
 *       {@code \t} and the Unicode {@code U+2028}/{@code U+2029}, which are line breaks to many
 *       readers but not ISO controls — as visible escapes, so <b>one event is one line</b> and no
 *       embedded newline can forge a log record of its own.
 * </ol>
 *
 * <p>Cap before flatten deliberately: the truncation marker is written with a newline in it, the
 * same form {@link #capTail}'s twin uses, and flattening last neutralizes that newline too. The
 * cap therefore bounds the <em>input</em> to the flattening, not the output: a kept character that
 * renders as an escape grows, so a capped text of nothing but {@code U+2028} leaves as six
 * characters per one — the worst case, and still a bound (about 12 KB for the default cap) rather
 * than the unbounded flood the cap exists to stop.
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.app.findings.FindingsSanitizer}: both must
 * strip the same ANSI/control vocabulary and cap with the same tail semantics. Only that subset —
 * newline handling deliberately differs, because findings preserve line structure and log lines
 * destroy it. The two guard different trust boundaries and stay free of any production edge
 * between them — this leaf carries no internal dependency at all, and the plugin API publishes a
 * one-declared-dependency contract — so the reference above is plain text rather than a javadoc
 * link: neither type is on the other's classpath. An executable equivalence spec over one
 * adversarial corpus is what keeps the shared table from drifting. See
 * {@code .claude/rules/manual-sync-pairs.md} and design D5 of harden-logging-observability.
 *
 * <p>Implements FR6, NFR-S1 of harden-logging-observability.
 */
public final class LogText {

    /**
     * Max characters {@link #forLog(String)} keeps from the tail of one text: enough for a stack
     * trace or an assertion failure, small enough that a hostile multi-megabyte output cannot
     * flood the log. Same value as the findings sanitizer's cap — the shared tail-cap semantics.
     */
    public static final int DEFAULT_CAP_CHARS = 2_000;

    /**
     * ANSI escape sequences: CSI ({@code ESC [ params intermediates final}), OSC ({@code ESC ] …
     * BEL} or {@code ESC ] … ST}), and single-character Fe escapes. Any ESC the pattern does not
     * match is removed by the control-character filter.
     */
    private static final Pattern ANSI = Pattern.compile(
            "\\u001B(?:\\[[0-9;?]*[ -/]*[@-~]|][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)?|[@-Z\\\\-_])");

    /**
     * {@code U+2028} LINE SEPARATOR and {@code U+2029} PARAGRAPH SEPARATOR, written as numeric
     * constants rather than character literals: both are invisible in a source file, and a
     * backslash-u escape inside a char literal is expanded by the Java lexer before parsing,
     * which would make the literal unparseable.
     */
    private static final char LINE_SEPARATOR = 0x2028;

    private static final char PARAGRAPH_SEPARATOR = 0x2029;

    private LogText() {}

    /**
     * Prepares {@code text} for a log line: strip, cap at {@value #DEFAULT_CAP_CHARS} characters,
     * flatten. The default entry point — a call site that needs a different bound says so with
     * {@link #forLog(String, int)}.
     *
     * @param text the raw untrusted text; never null
     * @return one inert line's worth of text; never null, never containing a line break
     */
    public static String forLog(String text) {
        return forLog(text, DEFAULT_CAP_CHARS);
    }

    /**
     * {@link #forLog(String)} with an explicit character bound, for sites whose useful excerpt is
     * shorter (a decision-file preview) or longer (a captured build log).
     *
     * @param text the raw untrusted text; never null
     * @param cap the maximum characters kept before flattening; positive
     * @return one inert line's worth of text; never null, never containing a line break
     * @throws IllegalArgumentException if {@code cap} is not positive
     */
    public static String forLog(String text, int cap) {
        return flatten(capTail(strip(text), cap));
    }

    /**
     * Removes ANSI escape sequences and control characters (keeping {@code \n} and {@code \t}) from
     * {@code text} without truncating or flattening it. The half shared with the findings
     * sanitizer.
     *
     * @param text the raw untrusted text; never null
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
     * Keeps only the last {@code cap} characters of {@code text}, prepending a marker naming what
     * was dropped — the tail carries the error in typical command output, so capping keeps the
     * signal while bounding hostile volume. The other half shared with the findings sanitizer.
     *
     * @param text the text to bound; never null
     * @param cap the maximum characters to keep; positive
     * @return {@code text} unchanged when within the cap, else its marked tail; never null
     * @throws IllegalArgumentException if {@code cap} is not positive
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
     * Renders every line separator as a visible escape so the result occupies exactly one log line.
     * Covers what {@link #strip} deliberately keeps ({@code \n}, {@code \r}, {@code \t}) and the
     * two Unicode separators it never saw ({@code U+2028}, {@code U+2029}), which are not ISO
     * controls but do break lines for many readers — the forgery vector a control-only filter
     * misses.
     *
     * @param text the text to render on one line; never null
     * @return the flattened text; never null, never containing a line break
     */
    public static String flatten(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case LINE_SEPARATOR -> out.append("\\u2028");
                case PARAGRAPH_SEPARATOR -> out.append("\\u2029");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * A character {@link #strip} removes: ISO controls except {@code \n} and {@code \t}, DEL, and
     * the C1 range — the carriers of cursor tricks and log forgery.
     */
    private static boolean isStrippedControl(char c) {
        if (c == '\n' || c == '\t') {
            return false;
        }
        return c < 0x20 || (c >= 0x7F && c <= 0x9F);
    }
}
