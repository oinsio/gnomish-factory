package com.github.oinsio.gnomish.untrustedtext;

/**
 * The factory's one owner of untrusted-text neutralization (FR1, NFR-S1 of
 * split-logtext-leaves): agent/LLM output, subprocess stderr, tracker-sourced strings and
 * in-container command output are all attacker-influenced, and every plane that renders them —
 * a log line, an operator's terminal, a published finding — needs the same answer to "which
 * characters are hostile". That answer is {@link CharacterTable}, read through the primitives
 * below and through nothing else.
 *
 * <ul>
 *   <li>{@link #strip} removes everything {@link CharacterTable} names — escape sequences whole,
 *       payload included, and every neutralized character except {@code \n} and {@code \t};
 *   <li>{@link #capTail} bounds the volume of one excerpt, keeping the tail (where the error is)
 *       and naming what was dropped;
 *   <li>{@link #flatten} renders the surviving line separators as visible escapes
 *       ({@link LineFlattening}), so <b>one event is one line</b>;
 *   <li>{@link #forConsole} renders the same table <em>visibly</em> instead of removing it
 *       ({@link ConsoleNotation}), because an operator being attacked must see the attempt;
 *   <li>{@link #capRecord} bounds one rendered record component at the sink, keeping the head;
 *   <li>{@link #forLog}, {@link #forComment} and {@link #forCommentInline} compose the log-plane
 *       and tracker-comment exits.
 * </ul>
 *
 * <p>The log plane's {@code strip → capTail → flatten} is {@link #forLog}, owned here because
 * {@link UntrustedText} renders through it and may reach nothing above this leaf
 * (type-untrusted-text FR2); {@code logtext.LogText} delegates. The findings funnel's
 * {@code strip → capTail} stays its own in {@code app.findings.FindingsSanitizer#forLog}. Both
 * facades hold no character table, which is what makes "one table" a property of the build rather
 * than of two javadoc markers — {@code TextSafetyOwnerSpec} in {@code :bootstrap} asserts it.
 *
 * <p>Cap before flatten deliberately, in every caller that does both: the truncation marker is
 * written with a newline in it, and flattening last neutralizes that newline too. The cap therefore
 * bounds the <em>input</em> to the flattening, not the output — a kept character that renders as an
 * escape grows, so a capped text of nothing but {@code U+2028} leaves as six characters per one:
 * the worst case, and still a bound (about 12 KB for {@link #DEFAULT_CAP_CHARS}) rather than the
 * unbounded flood the cap exists to stop.
 *
 * <p>Implements FR1, NFR-S1 of split-logtext-leaves and FR2, NFR-S2 of type-untrusted-text;
 * originally FR6, NFR-S1 of harden-logging-observability and FR1, FR2, FR5 of
 * harden-untrusted-text-sinks.
 */
public final class TextSafety {

    /**
     * Max characters a log-line composition keeps from the tail of one text: enough for a stack
     * trace or an assertion failure, small enough that a hostile multi-megabyte output cannot
     * flood the log. One value for both facades — the shared tail-cap semantics.
     */
    public static final int DEFAULT_CAP_CHARS = 2_000;

    /**
     * Max characters {@link #capRecord} lets one rendered record component occupy, from
     * {@link RecordCap} where the bound and its derivation live.
     */
    public static final int RECORD_CAP_CHARS = RecordCap.CAP_CHARS;

    /**
     * Characters {@link #capRecord} reserves inside {@link #RECORD_CAP_CHARS} for its truncation
     * marker; {@link RecordCap}'s value, so the bound has one home.
     */
    static final int TRUNCATION_MARKER_RESERVE = RecordCap.MARKER_RESERVE;

    private TextSafety() {}

    /**
     * Prepares {@code text} for the operator's terminal: the second exit from {@link CharacterTable},
     * and the mirror image of the log plane. Where the log plane removes what the table names and
     * flattens the text to one line, this one renders the same set <em>visibly</em> — ESC as
     * {@code ^[}, the other C0 controls in caret notation, DEL as {@code ^?}, the C1 controls and
     * the widthless characters (bidirectional overrides, invisible formats, tag characters) as
     * backslash-u escapes, {@code \r} as the two characters {@code \r} — and keeps the two
     * characters the table does not name, {@code \n} and {@code \t}, along with the line
     * structure, exactly as they arrived. Nothing is dropped and no cap is applied — rendering a
     * character visibly makes the text longer, never shorter — because an operator report is long
     * by design and its reader is a person who needs all of it.
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
     * both facades share unchanged.
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
     * signal while bounding hostile volume. The other half both facades share.
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
     * half of the log plane, usable alone by a sink bounding a record it did not prepare.
     * {@link LineFlattening} holds what counts as a separator, and why.
     *
     * @param text the text to render on one line; never null
     * @return the flattened text; never null, never containing a line break
     */
    public static String flatten(String text) {
        return LineFlattening.render(text);
    }

    /**
     * Prepares {@code text} for a log line — {@link #strip}, {@link #capTail} at {@code cap},
     * {@link #flatten} — so one event is one line, inert and bounded.
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
     * Prepares {@code text} for a tracker comment ({@link CommentFencing}): stripped, mentions and
     * issue references broken, fenced under a label naming it machine output. Line structure kept,
     * no cap — a comment is read by a person, and a report is long by design.
     *
     * @param text the raw untrusted text; never null
     * @return the labeled, fenced block of stripped, mention-broken text; never null
     */
    public static String forComment(String text) {
        return CommentFencing.render(text);
    }

    /**
     * Prepares {@code text} for one untrusted <b>field</b> inside a tracker comment the factory
     * wrote itself ({@link CommentFencing#inert}): stripped, mentions and issue references broken,
     * but neither labeled nor fenced. The comment plane's second shape, for a report whose prose is
     * the factory's own — see {@link CommentFencing#inert} for why fencing such a report whole is
     * the wrong answer.
     *
     * @param text the raw untrusted text; never null
     * @return the inert field text, no label and no fence; never null
     */
    public static String forCommentInline(String text) {
        return CommentFencing.inert(text);
    }

    /**
     * Bounds one rendered record component — a formatted message, an MDC value, a whole rendered
     * throwable — the sink's cap rather than the choke point's ({@link RecordCap}): unlike
     * {@link #capTail} it keeps the <b>head</b>, where the operator-event code and a throwable's
     * top-level message live. {@value #RECORD_CAP_CHARS} sits above the widest {@link #forLog}
     * excerpt at {@link #DEFAULT_CAP_CHARS}, which passes byte for byte; one taken under a far
     * larger bound is cut here like any flood. Timestamp, level and logger are the pattern's, outside it.
     *
     * @param text the rendered record to bound; never null
     * @return {@code text} unchanged when within the cap, else its marked head; never null
     */
    public static String capRecord(String text) {
        return RecordCap.render(text);
    }
}
