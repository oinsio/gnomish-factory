package com.github.oinsio.gnomish.logtext;

/**
 * The choke point untrusted text passes before it becomes part of a log line (FR6 of
 * harden-logging-observability): agent/LLM output, subprocess stderr, tracker-sourced strings and
 * in-container command output are all attacker-influenced, and a log line is a record other people
 * read as evidence. Three neutralizations, in order:
 *
 * <ol>
 *   <li>{@link #strip} removes everything {@link CharacterTable} names — escape sequences whole,
 *       payload included, and every neutralized character except {@code \n} and {@code \t};
 *   <li>{@link #capTail} bounds the volume, keeping the tail (where the error is) and naming what
 *       was dropped;
 *   <li>{@link #flatten} renders the surviving line separators as visible escapes
 *       ({@link LineFlattening}), so <b>one event is one line</b>.
 * </ol>
 *
 * <p>Cap before flatten deliberately: the truncation marker is written with a newline in it, and
 * flattening last neutralizes that newline too. The cap therefore bounds the <em>input</em> to the
 * flattening, not the output — a kept character that renders as an escape grows, so a capped text
 * of nothing but {@code U+2028} leaves as six characters per one: the worst case, and still a bound
 * (about 12 KB for the default cap) rather than the unbounded flood the cap exists to stop.
 *
 * <p>Two exits read the one table: this class's {@link #forLog} for the log plane and
 * {@link #forConsole} for the operator's terminal, which shows what the log plane removes.
 * {@link #capRecord} is the sink's own bound on a whole rendered record, for text no choke point
 * prepared (FR1 of harden-untrusted-text-sinks).
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.app.findings.FindingsSanitizer}: both must
 * strip the same vocabulary — the {@link CharacterTable} this class owns — and cap with the same
 * tail semantics. Only that subset: newline handling deliberately differs, because findings
 * preserve line structure and log lines destroy it. The two guard different trust boundaries and
 * deliberately share no production edge, which is why the reference above is plain text rather than
 * a javadoc link — neither type is on the other's classpath — and why an executable equivalence
 * spec over one adversarial corpus, not the compiler, is what keeps the table from drifting. See
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
     * Max characters {@link #capRecord} lets one whole rendered record occupy: 16 KB (design D2 of
     * harden-untrusted-text-sinks). Derivation — {@link #forLog}'s own widest output is one
     * {@value #DEFAULT_CAP_CHARS}-character cap of six-character escapes, about 12 KB, so a
     * choke-point-prepared message can never reach this bound however many arguments a line
     * carries; and it is two orders of magnitude above the longest legitimate record the factory
     * emits (the per-task summary), while staying inside the async appender's queue budget.
     */
    public static final int RECORD_CAP_CHARS = 16_384;

    /**
     * Characters {@link #capRecord} reserves inside {@link #RECORD_CAP_CHARS} for its truncation
     * marker, so the marked result still fits the cap and a second pass is a no-op. Sized for the
     * widest marker the format can produce — both counts at {@code Integer.MAX_VALUE} — which
     * {@code LogTextRecordCapSpec} pins rather than leaving to arithmetic in a comment.
     */
    static final int TRUNCATION_MARKER_RESERVE = 64;

    private LogText() {}

    /**
     * Prepares {@code text} for a log line: strip, cap at {@value #DEFAULT_CAP_CHARS}, flatten. A
     * call site needing a different bound says so with {@link #forLog(String, int)}.
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
     * Prepares {@code text} for the operator's terminal: the second exit from {@link CharacterTable},
     * and the mirror image of {@link #forLog}. Where the log plane removes what the table names and
     * flattens the text to one line, this one renders the same set <em>visibly</em> — ESC as
     * {@code ^[}, the other C0 controls in caret notation, DEL as {@code ^?}, the widthless
     * characters as backslash-u escapes, {@code \r} as the two characters {@code \r} — and keeps
     * {@code \n}, the line structure and the length exactly as they arrived. No cap: an operator
     * report is long by design, and its reader is a person who needs all of it.
     *
     * @param text the raw untrusted text; never null
     * @return the text with nothing left a terminal would execute; never null
     */
    public static String forConsole(String text) {
        return ConsoleNotation.render(text);
    }

    /**
     * Removes every escape sequence and every {@link CharacterTable}-neutralized character (keeping
     * {@code \n} and {@code \t}) from {@code text} without truncating or flattening it. The half
     * shared with the findings sanitizer.
     *
     * @param text the raw untrusted text; never null
     * @return the stripped text; never null
     */
    public static String strip(String text) {
        String noSequences = CharacterTable.stripSequences(text);
        StringBuilder out = new StringBuilder(noSequences.length());
        // Code points, not chars: the tag block is astral, so a char-by-char walk would see two
        // surrogates it has no rule for and keep both.
        noSequences.codePoints().forEach(codePoint -> {
            if (!CharacterTable.isNeutralized(codePoint)) {
                out.appendCodePoint(codePoint);
            }
        });
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
     * Renders every line separator {@code text} carries as a visible escape — the one-event-one-line
     * half of {@link #forLog}, usable alone by a sink bounding a record it did not prepare.
     * {@link LineFlattening} holds what counts as a separator, and why.
     *
     * @param text the text to render on one line; never null
     * @return the flattened text; never null, never containing a line break
     */
    public static String flatten(String text) {
        return LineFlattening.render(text);
    }

    /**
     * Bounds one whole rendered record, the sink's cap rather than the choke point's. Unlike
     * {@link #capTail}, which keeps the tail because the error is at the end of command output,
     * this one keeps the <b>head</b>: the timestamp, the level, the logger and the operator-event
     * code live there, and a record that lost them is not findable at all. Over-cap text is cut to
     * the head and a visible marker naming the drop is appended, within the same bound — so the
     * result is never longer than the cap and a second pass finds nothing to do (FR2).
     *
     * <p>{@value #RECORD_CAP_CHARS} sits above anything {@link #forLog} can produce — its widest
     * output is one default cap of six-character escapes, about 12 KB — so a message the choke
     * point prepared passes here byte for byte, whatever else the record carries.
     *
     * @param text the rendered record to bound; never null
     * @return {@code text} unchanged when within the cap, else its marked head; never null
     */
    public static String capRecord(String text) {
        if (text.length() <= RECORD_CAP_CHARS) {
            return text;
        }
        int headChars = RECORD_CAP_CHARS - TRUNCATION_MARKER_RESERVE;
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
