package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a "List issue comments" response body into {@link ParsedMarker}
 * values, in comment (posting) order, skipping any comment that carries no
 * recognizable {@code gnomish} structural marker (an operator's own reply).
 * Shared by {@link GithubAbortFactsReader} (unconditional fold, safe only for
 * {@code Ready} issues) and {@link GithubTaskFetcher} (boundary-anchored via
 * {@link GithubCommentBoundary}), so both read the same comment-order list
 * shape from one place.
 *
 * <p>Implements FR7, FR8 of add-tracker-port.
 */
final class GithubCommentParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GithubCommentParser() {}

    static List<ParsedMarker> parseMarkers(String commentsJson) {
        try {
            JsonNode array = MAPPER.readTree(commentsJson);
            List<ParsedMarker> markers = new ArrayList<>();
            for (JsonNode comment : array) {
                GithubMarker.parse(comment.get("body").asText()).ifPresent(markers::add);
            }
            return List.copyOf(markers);
        } catch (JsonProcessingException e) {
            throw new GithubFeedQueryException("Failed to parse issue comments response body", e);
        }
    }
}
