package com.github.oinsio.gnomish.app.findings;

import java.util.regex.Pattern;

/**
 * The sanitization half of the findings funnel: environment-derived findings text passes
 * through here before it reaches any sink, so ANSI/terminal escape sequences and control
 * characters are stripped once, in one tested place, and log volume is bounded by a tail cap
 * noting truncation. Findings as <em>data</em> are deliberately untouched — {@code state.json}
 * carries them in full — sanitization applies at the sinks: log lines through {@link #forLog},
 * tracker publication through the engine's fenced-publication renderer.
 *
 * <p>Stripping removes ANSI CSI/OSC/Fe sequences, every ISO control character except
 * {@code \n} and {@code \t} (including DEL and the C1 range), and the Unicode bidirectional
 * overrides and isolates, neutralizing terminal-escape and text-reordering attacks on the
 * operator's console and log processors while keeping the text's line structure readable.
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.logtext.LogText}: both must strip the same
 * ANSI/control vocabulary and cap with the same tail semantics. Only that subset — newline
 * handling deliberately differs, because findings preserve line structure and log lines destroy
 * it. The two are separate controls at separate trust boundaries (findings entering a sink here,
 * untrusted text entering a log line there) and deliberately share no production edge: this module
 * publishes a one-declared-dependency contract, so a {@code :logtext} import would enter its POM
 * and couple its japicmp baseline to another artifact's semver. What keeps the shared table from
 * drifting is an executable equivalence spec over one adversarial corpus, not the compiler — which
 * is why the reference above is plain text: the type is deliberately not on this module's
 * classpath, so no javadoc link to it could resolve. See {@code .claude/rules/manual-sync-pairs.md}
 * and design D5 of harden-logging-observability.
 *
 * <p>Published here rather than in {@code application} because the invariant is contract-grade:
 * an external-check plugin sinks untrusted machine output (CI log tails, command stderr) into
 * findings exactly as first-party adapters do, so it must be able to apply the same hygiene with
 * this module as its only declared dependency (design D3).
 *
 * <p>Implements FR15, NFR-C1 of add-sandbox-core; FR2, NFR-S1 of
 * close-plugin-api-compilability-gap.
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
            "\\u001B(?:\\[[0-9;?]*[ -/]*[@-~]|][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)?|[@-Z\\\\-_])");

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
        // The cap counts UTF-16 units, so the boundary can land between the two halves of an
        // astral character. Keeping the low half alone emits an unpaired surrogate, which every
        // UTF-8 sink downstream renders as a replacement character — evidence the reader cannot
        // tell from a real one. Dropping it costs one character of an already-truncated tail.
        int start = text.length() - cap;
        if (Character.isLowSurrogate(text.charAt(start)) && Character.isHighSurrogate(text.charAt(start - 1))) {
            start++;
        }
        String tail = text.substring(start);
        return "[truncated, showing last %d of %d chars]\n%s".formatted(tail.length(), text.length(), tail);
    }

    /**
     * A character {@link #strip} removes: ISO controls except {@code \n} and {@code \t},
     * DEL, the C1 range, and the bidirectional overrides — the carriers of cursor tricks and
     * log forgery.
     */
    private static boolean isStrippedControl(char c) {
        if (c == '\n' || c == '\t') {
            return false;
        }
        return c < 0x20 || (c >= 0x7F && c <= 0x9F) || isBidiOverride(c);
    }

    /**
     * The bidirectional embedding/override controls {@code U+202A}–{@code U+202E} and the
     * isolates {@code U+2066}–{@code U+2069}. Not ISO controls, so a control-only filter keeps
     * them, but they reorder everything the reader sees after them: the Trojan Source vector,
     * where the rendered text stops matching the recorded text. That is the same claim about the
     * evidence that an ANSI cursor sequence makes, so they leave by the same door.
     */
    private static boolean isBidiOverride(char c) {
        return (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069);
    }
}
