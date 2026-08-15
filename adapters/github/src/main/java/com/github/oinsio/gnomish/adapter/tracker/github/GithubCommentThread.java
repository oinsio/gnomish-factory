package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the full raw comment list of a GitHub issue, in posting order,
 * keeping every comment's {@code id}/{@code body}/{@code created_at} whether
 * or not it is a recognizable {@code gnomish} structural marker (design D13,
 * FR12 of add-tracker-port). This is the complement of {@link
 * GithubCommentParser}: that class discards non-marker comments (it only
 * returns parsed markers), which is fine for boundary-anchoring and abort
 * folding, but {@link GithubDecisions} needs the opposite — every comment,
 * classified locally as marker-or-plain-reply — so this class exists to
 * share that one HTTP call and JSON walk without duplicating it.
 *
 * <p>Implements FR12 of add-tracker-port.
 */
record GithubCommentThread(GithubHttpClient httpClient) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Fetches all comments of {@code owner/repo#issueNumber}, in posting order. */
    List<RawComment> fetchAll(String owner, String repo, int issueNumber) {
        String path = "/repos/%s/%s/issues/%d/comments?per_page=100".formatted(owner, repo, issueNumber);
        HttpRequest.Builder request = httpClient.newRequest(path).GET();
        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() / 100 != 2) {
            throw new GithubFeedQueryException("Failed to fetch comments for %s/%s#%d: HTTP %d"
                    .formatted(owner, repo, issueNumber, response.statusCode()));
        }
        return parse(response.body(), owner, repo, issueNumber);
    }

    private static List<RawComment> parse(String commentsJson, String owner, String repo, int issueNumber) {
        try {
            JsonNode array = MAPPER.readTree(commentsJson);
            List<RawComment> comments = new ArrayList<>();
            for (JsonNode comment : array) {
                comments.add(new RawComment(
                        comment.get("id").asLong(),
                        comment.get("body").asText(),
                        Instant.parse(comment.get("created_at").asText())));
            }
            return List.copyOf(comments);
        } catch (JsonProcessingException e) {
            throw new GithubFeedQueryException(
                    "Failed to parse comments response for %s/%s#%d".formatted(owner, repo, issueNumber), e);
        }
    }

    /** A single raw GitHub comment, before classifying it as a structural marker or a plain reply. */
    record RawComment(long id, String body, Instant createdAt) {}
}
