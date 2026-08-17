package com.github.oinsio.gnomish.adapter.check.http;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One declarative predicate over an http response — the unit both {@code pass-when} and {@code
 * pending-when} are written in (FR10, design D4 of add-plugin-architecture). It extracts a value
 * from the body (a jsonPath step, a regex step, or a jsonPath step feeding a regex one) and
 * compares it with {@code equals}.
 *
 * <p>A condition with no extractor is the {@link #alwaysMatching() bare} one: it asserts nothing
 * about the body, which is what makes the default {@code pass-when} "HTTP 2xx and nothing else" and
 * lets the whole {@code params} block stay optional for a plain probe. Whether the status counts is
 * the caller's business, not this predicate's — {@link HttpExternalCheckClient} requires 2xx for a
 * pass and ignores it for pending, so a service that reports "still running" with a non-2xx status
 * is still pollable.
 *
 * <p>Its two extractors compose in the order they are declared: jsonPath selects a node, the regex
 * then extracts from that node's text. Nothing here throws — an unmatched extraction is a
 * non-match, so a surprising response shape produces a check verdict, never a crash mid-poll.
 *
 * <p>A plain final class rather than the record its three values suggest: PIT's Gregor engine
 * hot-swaps mutated bytecode into an already-loaded class, and the JVM refuses a redefinition that
 * touches a class's {@code Record} attribute — every mutation here came back RUN_ERROR (the minion
 * crashed before running a test) instead of KILLED, which silently removed the whole type from the
 * mutation gate (hcoles/pitest#1285; `.claude/rules/testing.md`). Dropping the record shape restores
 * it.
 *
 * <p>Implements FR10 of add-plugin-architecture.
 */
final class HttpCheckCondition {

    /** The subset-dialect jsonPath selector, or {@code null} when none is declared. */
    private final @Nullable String jsonPath;
    /** The extraction pattern, or {@code null} when none is declared. */
    private final @Nullable String regex;
    /** The value the extraction must equal; {@code null} exactly when no extractor is declared. */
    private final @Nullable String expected;

    HttpCheckCondition(@Nullable String jsonPath, @Nullable String regex, @Nullable String expected) {
        this.jsonPath = jsonPath;
        this.regex = regex;
        this.expected = expected;
    }

    /** The manifest keys this condition is written with, shared with the params validator. */
    static final String JSON_PATH_KEY = "json-path";

    static final String REGEX_KEY = "regex";
    static final String EQUALS_KEY = "equals";

    /** The condition asserting nothing about the body — the default {@code pass-when} (FR10). */
    static HttpCheckCondition alwaysMatching() {
        return new HttpCheckCondition(null, null, null);
    }

    /**
     * Reads a condition out of one raw params sub-map, taking every key verbatim as a string. The
     * shape is already graded by {@link HttpCheckParamsValidator} at the load seam, so nothing is
     * rejected here.
     *
     * @param raw the {@code pass-when} / {@code pending-when} sub-map; never null
     * @return the parsed condition; never null
     */
    static HttpCheckCondition from(Map<String, Object> raw) {
        return new HttpCheckCondition(
                HttpCheckParams.string(raw, JSON_PATH_KEY),
                HttpCheckParams.string(raw, REGEX_KEY),
                HttpCheckParams.string(raw, EQUALS_KEY));
    }

    /** True when this condition looks at the body at all, rather than only at the status. */
    boolean hasExtractor() {
        return jsonPath != null || regex != null;
    }

    /**
     * Extracts this condition's value from {@code body}, applying whichever extractors are declared
     * in order.
     *
     * @param body the raw response body
     * @return the extracted text, or empty when an extractor selected nothing
     */
    Optional<String> extract(String body) {
        String selected = body;
        if (jsonPath != null) {
            Optional<String> node = HttpBodyExtractor.json(body, jsonPath);
            if (node.isEmpty()) {
                return Optional.empty();
            }
            selected = node.get();
        }
        return regex == null ? Optional.of(selected) : HttpBodyExtractor.regex(selected, regex);
    }

    /**
     * True when this condition's extraction equals its declared value. A condition with no
     * extractor matches every body — the "2xx and nothing else" default.
     *
     * @param body the raw response body
     * @return whether the body satisfies this condition
     */
    boolean matches(String body) {
        if (!hasExtractor()) {
            return true;
        }
        return extract(body).filter(value -> value.equals(expected)).isPresent();
    }

    /** The human-facing rendering of this condition, for a failing check's findings (NFR-O1). */
    String describe() {
        if (!hasExtractor()) {
            return "HTTP 2xx";
        }
        String selector = jsonPath != null ? JSON_PATH_KEY + " '" + jsonPath + "'" : "";
        String pattern = regex != null ? (selector.isEmpty() ? "" : " then ") + REGEX_KEY + " '" + regex + "'" : "";
        return selector + pattern + " equals '" + expected + "'";
    }
}
