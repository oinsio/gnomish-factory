package com.github.oinsio.gnomish.untrustedtext;

import java.util.regex.Pattern;

/**
 * The one character-class table the factory's text exits read: {@link TextSafety#strip} for the log
 * plane, which removes what this table names, and {@link TextSafety#forConsole} for the operator's
 * terminal, which renders the same set visibly instead. Separate from {@link TextSafety} so that
 * "which characters are hostile" has one owner and "how each plane renders them" has another. Both
 * facades over {@link TextSafety} — the log-line sanitizer {@code logtext.LogText} and the findings
 * sanitizer {@code app.findings.FindingsSanitizer} — read this table through it and hold none of
 * their own, so there is nothing here to keep in step by hand.
 *
 * <p>Two shapes of hostility, and they need different tools:
 *
 * <ul>
 *   <li><b>Sequences</b> — an introducer plus a body that a terminal consumes as a command. The
 *       body is ordinary printable text, so removing the introducer alone would leave {@code 52;c;…}
 *       on the line: {@link #stripSequences} takes the whole sequence.
 *   <li><b>Characters</b> — a code point that is itself the weapon: a control, a bidirectional
 *       override, an invisible format character, a tag character. {@link #isNeutralized} names them.
 * </ul>
 *
 * <p>The 8-bit C1 forms of the introducers ({@code U+009B} CSI, {@code U+009D} OSC and the rest)
 * are deliberately handled as characters, not as introducers: the character rule already removes
 * every one of them, so what a terminal would have executed arrives as inert text, and treating
 * them as introducers would let one stray C1 byte in mis-decoded subprocess output swallow the rest
 * of the diagnosis. The sequence rule therefore reads only the {@code ESC}-introduced forms.
 *
 * <p>Implements FR1, NFR-S1 of harden-untrusted-text-sinks.
 */
final class CharacterTable {

    /**
     * The {@code ESC}-introduced sequences, in match order: CSI ({@code ESC [ params intermediates
     * final}); the five string types — OSC, DCS, SOS, PM, APC ({@code ESC ] P X ^ _}) — each running
     * to its terminator, ST ({@code ESC \} or {@code U+009C}) or BEL, or to the end of the text when
     * the attacker left it open, which is what a terminal does with it too; and the single-character
     * Fe escapes. The string alternative precedes the Fe one because {@code ESC P} and {@code ESC X}
     * match both, and only the longer reading consumes the payload. Any {@code ESC} no alternative
     * matches is removed by {@link #isNeutralized}.
     */
    private static final Pattern ANSI_SEQUENCE = Pattern.compile("\\u001B(?:\\[[0-9;?]*[ -/]*[@-~]"
            + "|[\\]P^_X][^\\u0007\\u001B\\u009C]*(?:\\u0007|\\u001B\\\\|\\u009C)?"
            + "|[@-Z\\\\-_])");

    private CharacterTable() {}

    /**
     * Removes every escape sequence — introducer and payload — leaving the surrounding text.
     *
     * @param text the raw untrusted text; never null
     * @return the text with no escape sequence left; never null
     */
    static String stripSequences(String text) {
        return ANSI_SEQUENCE.matcher(text).replaceAll("");
    }

    /**
     * Whether {@code codePoint} is a character the factory neutralizes on both planes: ISO controls
     * except {@code \n} and {@code \t} (DEL and the whole C1 range included), the bidirectional
     * overrides and isolates, the invisible format characters, and the tag block.
     *
     * @param codePoint the code point to classify
     * @return true when the character must not reach a log line or a terminal as itself
     */
    static boolean isNeutralized(int codePoint) {
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
     * The bidirectional embedding/override controls {@code U+202A}–{@code U+202E} and the isolates
     * {@code U+2066}–{@code U+2069}. Not ISO controls, so a control-only filter keeps them, but they
     * reorder everything the reader sees after them: the Trojan Source vector, where the rendered
     * line stops matching the recorded one. That is the same claim about the evidence that an ANSI
     * cursor sequence makes, so they leave by the same door.
     */
    private static boolean isBidiOverride(int codePoint) {
        return (codePoint >= 0x202A && codePoint <= 0x202E) || (codePoint >= 0x2066 && codePoint <= 0x2069);
    }

    /**
     * The characters that render as nothing at all: the zero-width set and the directional marks
     * {@code U+200B}–{@code U+200F}, the invisible operators {@code U+2060}–{@code U+2064}, and
     * {@code U+FEFF}. They carry no width, so two texts that differ only in these are the same text
     * to every reader — which is exactly how a recorded line is made to disagree with the line an
     * operator compares it against, and how an instruction is smuggled past a human reviewer.
     */
    private static boolean isInvisibleFormat(int codePoint) {
        return (codePoint >= 0x200B && codePoint <= 0x200F)
                || (codePoint >= 0x2060 && codePoint <= 0x2064)
                || codePoint == 0xFEFF;
    }

    /**
     * The tag block {@code U+E0000}–{@code U+E007F}: an astral mirror of ASCII that renders as
     * nothing, so a whole sentence can ride invisibly inside an issue title or a stage name. Astral
     * means each one is a surrogate pair in UTF-16, which is why both ends walk code points rather
     * than {@code char}s — a char-by-char filter cannot see this class at all.
     */
    private static boolean isTagCharacter(int codePoint) {
        return codePoint >= 0xE0000 && codePoint <= 0xE007F;
    }
}
