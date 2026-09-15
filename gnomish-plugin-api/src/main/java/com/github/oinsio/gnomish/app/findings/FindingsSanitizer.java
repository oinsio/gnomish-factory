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
 * <p>Stripping removes the {@code ESC}-introduced sequences whole — CSI and the five string types
 * (OSC, DCS, SOS, PM, APC), payload included — every ISO control character except {@code \n} and
 * {@code \t} (DEL and the C1 range included), the Unicode bidirectional overrides and isolates, the
 * invisible format characters (the zero-width set, the invisible operators, {@code U+FEFF}) and the
 * tag block {@code U+E0000}–{@code U+E007F}. Together they neutralize terminal-escape,
 * text-reordering and invisible-smuggling attacks on the operator's console and log processors
 * while keeping the text's line structure readable.
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
     * The {@code ESC}-introduced sequences, in match order: CSI ({@code ESC [ params intermediates
     * final}); the five string types — OSC, DCS, SOS, PM, APC ({@code ESC ] P X ^ _}) — each
     * running to its terminator, ST ({@code ESC \\} or {@code U+009C}) or BEL, or to the end of the
     * text when the attacker left it open, which is what a terminal does with it too; and the
     * single-character Fe escapes. The string alternative precedes the Fe one because {@code ESC P}
     * and {@code ESC X} match both, and only the longer reading consumes the payload. Any ESC the
     * pattern does not match is removed by the character filter, as are the 8-bit C1 introducers:
     * treating those as introducers would let one stray C1 byte in mis-decoded output swallow the
     * rest of a finding, and the payload they leave behind is inert text either way.
     */
    private static final Pattern ANSI = Pattern.compile("\\u001B(?:\\[[0-9;?]*[ -/]*[@-~]"
            + "|[\\]P^_X][^\\u0007\\u001B\\u009C]*(?:\\u0007|\\u001B\\\\|\\u009C)?"
            + "|[@-Z\\\\-_])");

    private FindingsSanitizer() {}

    /**
     * Strips escape sequences and neutralized characters (keeping {@code \n} and {@code \t}) from
     * {@code text} without truncating it (FR15). Implements FR1, NFR-S1 of
     * harden-untrusted-text-sinks.
     *
     * @param text the raw environment-derived text; never null
     * @return the stripped text; never null
     */
    public static String strip(String text) {
        String noAnsi = ANSI.matcher(text).replaceAll("");
        StringBuilder out = new StringBuilder(noAnsi.length());
        // Code points, not chars: the tag block is astral, so a char-by-char walk would see two
        // surrogates it has no rule for and keep both.
        noAnsi.codePoints().forEach(codePoint -> {
            if (!isStrippedControl(codePoint)) {
                out.appendCodePoint(codePoint);
            }
        });
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
     * A character {@link #strip} removes: ISO controls except {@code \n} and {@code \t}, DEL, the
     * C1 range, the bidirectional overrides, the invisible format characters and the tag block —
     * the carriers of cursor tricks, of log forgery, and of text that differs from what any reader
     * of it sees.
     */
    private static boolean isStrippedControl(int codePoint) {
        if (codePoint == '\n' || codePoint == '\t') {
            return false;
        }
        return codePoint < 0x20
                || (codePoint >= 0x7F && codePoint <= 0x9F)
                || isBidiOverride(codePoint)
                || isInvisibleFormat(codePoint)
                || isTagCharacter(codePoint);
    }

    /**
     * The bidirectional embedding/override controls {@code U+202A}–{@code U+202E} and the
     * isolates {@code U+2066}–{@code U+2069}. Not ISO controls, so a control-only filter keeps
     * them, but they reorder everything the reader sees after them: the Trojan Source vector,
     * where the rendered text stops matching the recorded text. That is the same claim about the
     * evidence that an ANSI cursor sequence makes, so they leave by the same door.
     */
    private static boolean isBidiOverride(int codePoint) {
        return (codePoint >= 0x202A && codePoint <= 0x202E) || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    /**
     * The characters that render as nothing at all: the zero-width set and the directional marks
     * {@code U+200B}–{@code U+200F}, the invisible operators {@code U+2060}–{@code U+2064}, and
     * {@code U+FEFF}. They carry no width, so two findings that differ only in these are the same
     * finding to every reader — which is how a recorded finding is made to disagree with what an
     * operator compares it against, and how an instruction is smuggled past a human reviewer.
     */
    private static boolean isInvisibleFormat(int codePoint) {
        return (codePoint >= 0x200B && codePoint <= 0x200F)
                || (codePoint >= 0x2060 && codePoint <= 0x2064)
                || codePoint == 0xFEFF;
    }

    /**
     * The tag block {@code U+E0000}–{@code U+E007F}: an astral mirror of ASCII that renders as
     * nothing, so a whole sentence can ride invisibly inside a finding. Astral means each one is a
     * surrogate pair in UTF-16, which is why {@link #strip} walks code points rather than
     * {@code char}s — a char-by-char filter cannot see this class at all.
     */
    private static boolean isTagCharacter(int codePoint) {
        return codePoint >= 0xE0000 && codePoint <= 0xE007F;
    }
}
