package com.github.oinsio.gnomish.app.findings;

import com.github.oinsio.gnomish.untrustedtext.TextSafety;

/**
 * The sanitization half of the findings funnel: environment-derived findings text passes
 * through here before it reaches any sink, so ANSI/terminal escape sequences and control
 * characters are stripped once, in one tested place, and log volume is bounded by a tail cap
 * noting truncation. Findings as <em>data</em> are deliberately untouched — {@code state.json}
 * carries them in full — sanitization applies at the sinks: log lines through {@link #forLog},
 * tracker publication through the engine's fenced-publication renderer.
 *
 * <p>Stripping removes the {@code ESC}-introduced sequences whole — CSI and the five string types
 * (OSC, DCS, SOS, PM, APC), payload included — every ISO control character except {@code \n} and
 * {@code \t} (DEL and the C1 range included), the Unicode bidirectional overrides and isolates, the
 * invisible format characters (the zero-width set, the invisible operators, {@code U+FEFF}) and the
 * tag block {@code U+E0000}–{@code U+E007F}. Together they neutralize terminal-escape,
 * text-reordering and invisible-smuggling attacks on the operator's console and log processors
 * while keeping the text's line structure readable.
 *
 * <p>Which characters those are is not decided here. This class is a <b>facade</b> over
 * {@link TextSafety} in the JDK-only {@code :untrustedtext} leaf, the factory's one owner of the
 * character-class table (FR1, FR3 of split-logtext-leaves); the log-line sanitizer
 * {@code logtext.LogText} is the other facade over the same owner. What remains this class's own is
 * the funnel's policy — its {@value #LOG_TAIL_CAP_CHARS}-character bound, and the deliberate
 * difference that findings keep their line structure where a log line destroys it. The leaf is
 * JDK-only, so reaching it costs this module nothing of its published promise: it declares no
 * logging API, no framework and no other factory type, and a third party still compiles against
 * one declared dependency.
 *
 * <p>Published here rather than in {@code application} because the invariant is contract-grade:
 * an external-check plugin sinks untrusted machine output (CI log tails, command stderr) into
 * findings exactly as first-party adapters do, so it must be able to apply the same hygiene with
 * this module as its only declared dependency (design D3).
 *
 * <p>Implements FR15, NFR-C1 of add-sandbox-core; FR2, NFR-S1 of
 * close-plugin-api-compilability-gap; FR3 of split-logtext-leaves.
 */
public final class FindingsSanitizer {

    /**
     * Max characters {@link #forLog} keeps from the tail of one text: enough for a stack
     * trace or assertion failure, small enough that a hostile multi-megabyte output cannot
     * flood the logs (NFR-C1). The owner's value, so the two facades' caps cannot drift.
     */
    static final int LOG_TAIL_CAP_CHARS = TextSafety.DEFAULT_CAP_CHARS;

    private FindingsSanitizer() {}

    /**
     * Strips escape sequences and neutralized characters (keeping {@code \n} and {@code \t}) from
     * {@code text} without truncating it (FR15), through {@link TextSafety#strip}. Implements FR1,
     * NFR-S1 of harden-untrusted-text-sinks.
     *
     * @param text the raw environment-derived text; never null
     * @return the stripped text; never null
     */
    public static String strip(String text) {
        return TextSafety.strip(text);
    }

    /**
     * Prepares {@code text} for a log line: {@link #strip} plus a {@value
     * #LOG_TAIL_CAP_CHARS}-character tail cap noting truncation (FR15, NFR-C1). The line structure
     * is deliberately kept — that is the funnel's whole difference from the log-line sanitizer,
     * which flattens it.
     *
     * @param text the raw environment-derived text; never null
     * @return the sanitized, bounded text; never null
     */
    public static String forLog(String text) {
        return capTail(strip(text), LOG_TAIL_CAP_CHARS);
    }

    /**
     * Keeps only the last {@code cap} characters of {@code text}, prepending a marker
     * naming what was dropped ({@link TextSafety#capTail}) — the tail carries the error in typical
     * build output, so capping keeps the signal while bounding hostile volume (NFR-C1).
     *
     * @param text the text to bound; never null
     * @param cap the maximum characters to keep; positive
     * @return {@code text} unchanged when within the cap, else its marked tail; never null
     * @throws IllegalArgumentException if {@code cap} is not positive
     */
    public static String capTail(String text, int cap) {
        return TextSafety.capTail(text, cap);
    }
}
