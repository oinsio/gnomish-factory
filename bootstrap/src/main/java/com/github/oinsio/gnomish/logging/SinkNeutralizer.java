package com.github.oinsio.gnomish.logging;

import com.github.oinsio.gnomish.logtext.LogText;

/**
 * The neutralization the three sink converters share, expressed entirely as calls into
 * {@code :logtext}: this class owns no vocabulary of its own, so "which characters are hostile"
 * keeps the single owner {@code CharacterTable} is (design D1 of harden-untrusted-text-sinks).
 *
 * <p>The composition is {@code strip} then {@code flatten} then {@code capRecord}, the same order
 * and the same primitives {@code LogText.forLog} uses — which is why it is idempotent over
 * choke-point output (FR2, design D2): a stripped text has nothing left to strip, a flattened one
 * has no separator left to flatten and the backslash-n it carries is plain text to a second
 * flatten, and {@code capRecord}'s bound sits above anything {@code forLog} can produce.
 *
 * <p>Implements FR1, FR2, FR4, NFR-R1 of harden-untrusted-text-sinks.
 */
final class SinkNeutralizer {

    private SinkNeutralizer() {}

    /**
     * Renders {@code text} as one inert, bounded line: the treatment FR1 asks of the formatted
     * message and FR4 of every MDC value.
     *
     * @param text the rendered text to neutralize; never null
     * @return the neutralized text; never null, never containing a line break
     */
    static String oneLine(String text) {
        return LogText.capRecord(LogText.flatten(LogText.strip(text)));
    }

    /**
     * The record a converter writes when neutralization itself failed (NFR-R1): bounded by
     * construction and control-free by construction, because both halves it interpolates are the
     * converter's own words and a JVM class name — never the value that failed. A converter that
     * rethrew here would lose the record and, on a repeated failure, the appender with it.
     *
     * @param field the part of the record that could not be neutralized, named for the reader
     * @param failure what the neutralization threw; only its type is quoted
     * @return the placeholder record; never null
     */
    static String placeholder(String field, Throwable failure) {
        return "[log sink could not neutralize the " + field + ": "
                + failure.getClass().getName() + "]";
    }
}
