package com.github.oinsio.gnomish.logtext;

import com.github.oinsio.gnomish.untrustedtext.TextSafety;

/**
 * The choke point untrusted text passes before it becomes part of a log line (FR6 of
 * harden-logging-observability): agent/LLM output, subprocess stderr, tracker-sourced strings and
 * in-container command output are all attacker-influenced, and a log line is a record other people
 * read as evidence. Three neutralizations, in order:
 *
 * <ol>
 *   <li>{@link #strip} removes everything the character table names — escape sequences whole,
 *       payload included, and every neutralized character except {@code \n} and {@code \t};
 *   <li>{@link #capTail} bounds the volume, keeping the tail (where the error is) and naming what
 *       was dropped;
 *   <li>{@link #flatten} renders the surviving line separators as visible escapes, so <b>one event
 *       is one line</b>.
 * </ol>
 *
 * <p>Cap before flatten deliberately: the truncation marker is written with a newline in it, and
 * flattening last neutralizes that newline too. The cap therefore bounds the <em>input</em> to the
 * flattening, not the output — a kept character that renders as an escape grows, so a capped text
 * of nothing but {@code U+2028} leaves as six characters per one: the worst case, and still a bound
 * (about 12 KB for the default cap) rather than the unbounded flood the cap exists to stop.
 *
 * <p>What this class owns is the <em>log plane's vocabulary</em>: which bound a log line takes by
 * default, and one import for every primitive a log-plane caller needs. The composition itself
 * moved down to {@link TextSafety#forLog} when the carrier type arrived (FR2 of
 * type-untrusted-text): {@code UntrustedText} renders its own log exit and may reach nothing above
 * that leaf, so the order the three primitives run in has to live there or exist twice. Which
 * characters are hostile, what each primitive does to them, and now how they compose, all belong
 * to {@link TextSafety} in the JDK-only {@code :untrustedtext} leaf — the one owner the findings
 * sanitizer reaches too, so neither facade can drift from the other (FR1, FR2 of
 * split-logtext-leaves). Every method below is that owner's, unchanged.
 *
 * <p>Implements FR6, NFR-S1 of harden-logging-observability; FR2 of split-logtext-leaves.
 */
public final class LogText {

    /**
     * Max characters {@link #forLog(String)} keeps from the tail of one text: enough for a stack
     * trace or an assertion failure, small enough that a hostile multi-megabyte output cannot
     * flood the log. The owner's value, shared with the findings sanitizer's own cap.
     */
    public static final int DEFAULT_CAP_CHARS = TextSafety.DEFAULT_CAP_CHARS;

    /**
     * Max characters {@link #capRecord} lets one whole rendered record occupy: 16 KB, sized by
     * {@link TextSafety#RECORD_CAP_CHARS} against the widest output {@link #forLog} can produce.
     */
    public static final int RECORD_CAP_CHARS = TextSafety.RECORD_CAP_CHARS;

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
        return TextSafety.forLog(text, cap);
    }

    /**
     * Prepares {@code text} for the operator's terminal — the mirror image of {@link #forLog}:
     * {@link TextSafety#forConsole} renders visibly what the log plane removes, and keeps the line
     * structure and the length the operator's report is written in.
     *
     * @param text the raw untrusted text; never null
     * @return the text with nothing left a terminal would execute; never null
     */
    public static String forConsole(String text) {
        return TextSafety.forConsole(text);
    }

    /**
     * Removes every escape sequence and every neutralized character (keeping {@code \n} and
     * {@code \t}) from {@code text} without truncating or flattening it
     * ({@link TextSafety#strip}).
     *
     * @param text the raw untrusted text; never null
     * @return the stripped text; never null
     */
    public static String strip(String text) {
        return TextSafety.strip(text);
    }

    /**
     * Keeps only the last {@code cap} characters of {@code text}, prepending a marker naming what
     * was dropped ({@link TextSafety#capTail}) — the tail carries the error in typical command
     * output, so capping keeps the signal while bounding hostile volume.
     *
     * @param text the text to bound; never null
     * @param cap the maximum characters to keep; positive
     * @return {@code text} unchanged when within the cap, else its marked tail; never null
     * @throws IllegalArgumentException if {@code cap} is not positive
     */
    public static String capTail(String text, int cap) {
        return TextSafety.capTail(text, cap);
    }

    /**
     * Renders every line separator {@code text} carries as a visible escape
     * ({@link TextSafety#flatten}) — the one-event-one-line half of {@link #forLog}, usable alone
     * by a sink bounding a record it did not prepare.
     *
     * @param text the text to render on one line; never null
     * @return the flattened text; never null, never containing a line break
     */
    public static String flatten(String text) {
        return TextSafety.flatten(text);
    }

    /**
     * Bounds one whole rendered record, the sink's cap rather than the choke point's
     * ({@link TextSafety#capRecord}): unlike {@link #capTail} it keeps the <b>head</b>, where the
     * timestamp, the level, the logger and the operator-event code live.
     *
     * @param text the rendered record to bound; never null
     * @return {@code text} unchanged when within the cap, else its marked head; never null
     */
    public static String capRecord(String text) {
        return TextSafety.capRecord(text);
    }
}
