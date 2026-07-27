package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Serializes the request body for a GitHub "create issue comment" POST — the
 * single-field {@code {"body": ...}} JSON payload shared by every comment-posting
 * GitHub adapter (claim, decisions, structural state writes, correspondence).
 *
 * <p>Consolidates a helper that was previously copy-pasted verbatim across those
 * adapters; the only difference between the copies was a word in the failure
 * message, which never surfaces in practice (serializing a record with one
 * {@code String} field cannot fail at runtime).
 *
 * <p>Implements FR1, FR14 of add-tracker-port.
 */
final class GithubCommentBody {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GithubCommentBody() {}

    /** Renders {@code {"body": text}} for a GitHub issue-comment POST. */
    static String toJson(String text) {
        try {
            return MAPPER.writeValueAsString(new Payload(text));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize comment request body", e);
        }
    }

    private record Payload(String body) {}
}
