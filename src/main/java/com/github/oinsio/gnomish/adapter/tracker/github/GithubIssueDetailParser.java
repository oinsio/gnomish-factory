package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a "Get an issue" response body into a {@link GithubIssueDetail}
 * (FR2, FR5 of add-tracker-port). Pure JSON parsing: does not call the API
 * and does not decide the logical {@code TrackerTaskState} — {@link
 * GithubTaskFetcher} owns that decision.
 *
 * <p>Implements FR2, FR5 of add-tracker-port.
 */
final class GithubIssueDetailParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GithubIssueDetailParser() {}

    static GithubIssueDetail parse(String responseBody) {
        try {
            JsonNode issue = MAPPER.readTree(responseBody);
            String title = issue.path("title").asText("");
            JsonNode bodyNode = issue.get("body");
            String body = bodyNode == null || bodyNode.isNull() ? null : bodyNode.asText();
            String state = issue.path("state").asText("open");
            JsonNode stateReasonNode = issue.get("state_reason");
            String stateReason = stateReasonNode == null || stateReasonNode.isNull() ? null : stateReasonNode.asText();
            List<String> labelNames = new ArrayList<>();
            for (JsonNode label : issue.path("labels")) {
                labelNames.add(label.path("name").asText(""));
            }
            return new GithubIssueDetail(title, body, state, stateReason, List.copyOf(labelNames));
        } catch (JsonProcessingException e) {
            throw new GithubFeedQueryException("Failed to parse Get an issue response body", e);
        }
    }
}
