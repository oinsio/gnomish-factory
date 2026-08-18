package com.github.oinsio.gnomish.adapter.check.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pulls the value a {@code pass-when} / {@code pending-when} condition compares out of one response
 * body (FR10, design D4 of add-plugin-architecture). Both extractors exist because CI and quality
 * REST APIs split between JSON status documents and plain-text or heterogeneous bodies; offering
 * only one would force an adapter for the other.
 *
 * <p>The jsonPath dialect is a deliberate subset — an optional {@code $} root, dot-separated field
 * names and {@code [n]} array indexes ({@code $.projectStatus.conditions[0].status}) — rather than
 * a full JSONPath engine: it addresses a status field in a status document, which is all a
 * pass/pending predicate needs (D4 rejects a full expression DSL for the same reason), and it keeps
 * the core free of another dependency. Anything the subset cannot address is regex territory.
 *
 * <p>Both extractors are total functions of the body: an unparseable document, a path that selects
 * nothing, or a regex that does not match yield {@link Optional#empty()}, which the condition reads
 * as "did not match" — never an exception, so a surprising response shape is a check verdict rather
 * than a crash inside the poll loop.
 *
 * <p>Implements FR10 of add-plugin-architecture.
 */
final class HttpBodyExtractor {

    /** Shared and stateless; {@code ObjectMapper} read paths are thread-safe once configured. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One path segment: a field name, optionally followed by any number of {@code [n]} indexes. */
    private static final Pattern SEGMENT = Pattern.compile("([^.\\[\\]]+)|\\[(\\d+)]");

    private HttpBodyExtractor() {}

    /**
     * Selects {@code jsonPath} in {@code body} and renders the selected node as text.
     *
     * @param body the raw response body
     * @param jsonPath the subset-dialect path (leading {@code $.} optional)
     * @return the selected scalar's text, or empty when the body is not JSON or the path selects
     *     nothing; a selected object or array renders as its compact JSON form
     */
    static Optional<String> json(String body, String jsonPath) {
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            return Optional.empty();
        }
        Matcher segments = SEGMENT.matcher(stripRoot(jsonPath));
        while (segments.find()) {
            node = step(node, segments);
            if (node == null || node.isMissingNode()) {
                return Optional.empty();
            }
        }
        return node.isNull() ? Optional.empty() : Optional.of(node.isValueNode() ? node.asText() : node.toString());
    }

    /**
     * Applies {@code regex} to {@code text} and returns capture group 1 when the pattern declares
     * one, otherwise the whole match — so {@code status=(\w+)} extracts the status while a
     * group-less pattern extracts what it matched.
     *
     * @param text the text to search (a raw body, or the output of a jsonPath step)
     * @param regex the extraction pattern
     * @return the extracted text, or empty when the pattern does not match; an invalid pattern also
     *     yields empty — the params validator has already reported it as a located load error
     */
    static Optional<String> regex(String text, String regex) {
        Matcher matcher;
        try {
            matcher = Pattern.compile(regex).matcher(text);
        } catch (PatternSyntaxException e) {
            return Optional.empty();
        }
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group());
    }

    /** One field-or-index step; {@code null} when the current node cannot take it. */
    private static JsonNode step(JsonNode node, Matcher segment) {
        String field = segment.group(1);
        return field != null ? node.path(field) : node.path(Integer.parseInt(segment.group(2)));
    }

    /** Drops the optional {@code $} root marker, so {@code $.a.b} and {@code a.b} are the same path. */
    private static String stripRoot(String jsonPath) {
        String trimmed = jsonPath.strip();
        return trimmed.startsWith("$") ? trimmed.substring(1) : trimmed;
    }
}
