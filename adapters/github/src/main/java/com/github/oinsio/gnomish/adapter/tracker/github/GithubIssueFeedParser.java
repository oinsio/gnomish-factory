package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a "List repository issues" JSON array body into the issues that are
 * actual tasks, excluding pull requests (github-tracker spec, "PR wearing
 * the ready label is not a task"): GitHub's documented way that a List
 * Issues entry is really a pull request is the presence of a {@code
 * pull_request} field on that entry (FR8 of add-tracker-port). Each
 * retained entry keeps its {@code number} AND {@code title} — the title
 * GitHub's list response already carries, so callers can enrich {@link
 * com.github.oinsio.gnomish.app.port.tracker.ReadyTask}/{@link
 * com.github.oinsio.gnomish.app.port.tracker.OpenTask} without an extra
 * per-issue fetch (FR7, NFR-P1 of add-board-command).
 *
 * <p>This class is pure JSON parsing: it does not call the API, does not
 * fetch comments, and does not build {@link
 * com.github.oinsio.gnomish.app.port.tracker.ReadyTask} values — {@link
 * GithubFeedQuery} owns the HTTP orchestration and abort-fact enrichment.
 *
 * <p>Implements FR8 of add-tracker-port. Implements FR7, NFR-P1 of
 * add-board-command (the {@code title} carried on each {@link IssueRef}).
 */
final class GithubIssueFeedParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GithubIssueFeedParser() {}

    /**
     * One retained List Issues entry: its issue number and title, exactly as
     * received on the wire (FR7, NFR-P1 of add-board-command).
     *
     * @param number the issue number
     * @param title the issue title, verbatim from the list response; never null
     */
    record IssueRef(int number, String title) {}

    /**
     * Parses {@code responseBody} (a JSON array from the List Issues
     * endpoint) and returns the number/title of entries that are not pull
     * requests, in array order.
     *
     * @param responseBody the raw JSON array response body
     * @return issue refs, in the order returned by the API; never null
     */
    static List<IssueRef> parseIssues(String responseBody) {
        try {
            JsonNode array = MAPPER.readTree(responseBody);
            List<IssueRef> issues = new ArrayList<>();
            for (JsonNode entry : array) {
                if (entry.has("pull_request")) {
                    continue;
                }
                issues.add(new IssueRef(
                        entry.get("number").asInt(), entry.path("title").asText("")));
            }
            return issues;
        } catch (JsonProcessingException e) {
            throw new GithubFeedQueryException("Failed to parse List Issues response body", e);
        }
    }
}
