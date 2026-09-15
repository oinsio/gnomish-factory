package com.github.oinsio.gnomish.logtext;

/**
 * The console plane's rendering of {@link CharacterTable}: it <em>shows</em> what the log plane
 * removes. The operator is the reader here, and an operator who is being attacked must see the
 * attempt — a silently dropped escape sequence tells them nothing, while a visible one names the
 * source as hostile. Line structure and volume are left alone: an escalation report is forty lines
 * long by design, and truncating it on the way to a terminal would cost the diagnosis the report
 * exists to carry.
 *
 * <p>The notation is not invented here. Caret notation for the C0 controls and DEL is what
 * {@code cat -v}, {@code less} and git's {@code sideband.allowControlCharacters} masking print;
 * backslash-u escapes for the characters that have no width to show are kubectl's
 * {@code EscapeTerminal} convention. An operator reading {@code ^[]52;c;…} knows what they are
 * looking at without a legend.
 *
 * <p>Two characters are deliberately not in caret notation. {@code \n} is kept as itself — it is
 * the line structure this plane preserves. {@code \r} is written as the two characters {@code \r}
 * rather than {@code ^M} because a carriage return's whole trick is to return to column 0 and
 * overwrite the line the operator just read, and naming it the way every programming language does
 * reads more plainly than a caret at that one site.
 *
 * <p>Implements FR5, NFR-O1, NFR-S2 of harden-untrusted-text-sinks.
 */
final class ConsoleNotation {

    private ConsoleNotation() {}

    /**
     * Renders every neutralized character of {@code text} visibly, keeping everything else — line
     * breaks and length included — exactly as it arrived.
     *
     * @param text the raw untrusted text; never null
     * @return the text with nothing left a terminal would execute; never null
     */
    static String render(String text) {
        StringBuilder out = new StringBuilder(text.length());
        // Code points, not chars: the tag block is astral, and naming it needs the whole code
        // point, not the two surrogates it is stored as.
        text.codePoints().forEach(codePoint -> append(out, codePoint));
        return out.toString();
    }

    private static void append(StringBuilder out, int codePoint) {
        if (codePoint == '\n') {
            out.append('\n');
        } else if (codePoint == '\r') {
            out.append("\\r");
        } else if (codePoint < 0x20) {
            // Caret notation is ASCII arithmetic: the control's code point plus 0x40 is the letter
            // the terminal key carries, so ESC (0x1B) reads as ^[ and NUL as ^@.
            out.append('^').append((char) (codePoint + 0x40));
        } else if (codePoint == 0x7F) {
            out.append("^?");
        } else if (CharacterTable.isNeutralized(codePoint)) {
            out.append(escape(codePoint));
        } else {
            out.appendCodePoint(codePoint);
        }
    }

    /**
     * The backslash-u escape naming {@code codePoint}. Four hex digits cannot name a code point
     * above the BMP — asked as {@code isBmpCodePoint} rather than as a {@code <= 0xFFFF} boundary,
     * which no input could ever sit on: {@code U+FFFF} is not a character this table names — so the
     * tag block takes the eight-digit long form C, Python and the Unicode standard's own notation use — rendering it as its two surrogates instead would name a pair of
     * halves no reader could look up.
     */
    private static String escape(int codePoint) {
        return Character.isBmpCodePoint(codePoint) ? "\\u%04X".formatted(codePoint) : "\\U%08X".formatted(codePoint);
    }
}
