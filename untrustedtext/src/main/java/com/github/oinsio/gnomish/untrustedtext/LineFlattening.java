package com.github.oinsio.gnomish.untrustedtext;

/**
 * The log plane's one-event-one-line rule: every line separator the text can carry is rendered as a
 * visible escape, so nothing a call site was handed can open a record of its own. The mirror image
 * of {@link ConsoleNotation}, which keeps line structure because its reader is a person; here the
 * reader is grep, and a record that spans two lines is a record that lies about how many events
 * happened.
 *
 * <p>Implements FR6 of harden-logging-observability.
 */
final class LineFlattening {

    /**
     * {@code U+2028} LINE SEPARATOR and {@code U+2029} PARAGRAPH SEPARATOR, written as numeric
     * constants rather than character literals: both are invisible in a source file, and a
     * backslash-u escape inside a char literal is expanded by the Java lexer before parsing,
     * which would make the literal unparseable.
     */
    private static final char LINE_SEPARATOR = 0x2028;

    private static final char PARAGRAPH_SEPARATOR = 0x2029;

    private LineFlattening() {}

    /**
     * Renders every line separator of {@code text} as a visible escape.
     * Covers what {@link TextSafety#strip} deliberately keeps ({@code \n}, {@code \t}) and the two
     * Unicode separators it never saw ({@code U+2028}, {@code U+2029}), which are not ISO controls but do
     * break lines for many readers — the forgery vector a control-only filter misses.
     *
     * <p>{@code \r} is escaped too, though {@link TextSafety#strip} removes it: flattening is offered
     * on its own and usable on text that never went through stripping, so leaving the one line
     * separator a caller is most likely to still hold would defeat the one-event-one-line promise.
     * Inside the log plane's strip-cap-flatten composition that arm is unreachable, which is why it
     * carries no round-trip through the pipeline of its own.
     *
     * @param text the text to render on one line; never null
     * @return the flattened text; never null, never containing a line break
     */
    static String render(String text) {
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
}
