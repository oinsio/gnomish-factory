package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a "List repository issues" JSON array body into the issue numbers
 * that are actual tasks, excluding pull requests (github-tracker spec, "PR
 * wearing the ready label is not a task"): GitHub's documented way that a
 * List Issues entry is really a pull request is the presence of a {@code
 * pull_request} field on that entry (FR8 of add-tracker-port).
 *
 * <p>This class is pure JSON parsing: it does not call the API, does not
 * fetch comments, and does not build {@link
 * com.github.oinsio.gnomish.app.port.tracker.ReadyTask} values — {@link
 * GithubFeedQuery} owns the HTTP orchestration and abort-fact enrichment.
 *
 * <p>Implements FR8 of add-tracker-port.
 */
final class GithubIssueFeedParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GithubIssueFeedParser() {}

    /**
     * Parses {@code responseBody} (a JSON array from the List Issues
     * endpoint) and returns the issue numbers of entries that are not pull
     * requests, in array order.
     *
     * @param responseBody the raw JSON array response body
     * @return issue numbers, in the order returned by the API; never null
     */
    static List<Integer> parseIssueNumbers(String responseBody) {
        try {
            JsonNode array = MAPPER.readTree(responseBody);
            List<Integer> numbers = new ArrayList<>();
            for (JsonNode entry : array) {
                if (entry.has("pull_request")) {
                    continue;
                }
                numbers.add(entry.get("number").asInt());
            }
            return numbers;
        } catch (JsonProcessingException e) {
            throw new GithubFeedQueryException("Failed to parse List Issues response body", e);
        }
    }
}
