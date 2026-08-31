package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

/**
 * The one find-then-upsert primitive every factory-authored tracker comment is
 * written through (design D7, FR11, UX3 of harden-task-branch-contract): look
 * for a comment already carrying the write's {@link GithubCommentIdentity},
 * update that comment in place when one exists, and POST a new comment only
 * when none does. A crash between an effect and its receipt therefore costs a
 * re-drive, never a duplicate report on the thread — and because the match is
 * on content identity rather than on the posting account, a resuming instance
 * updates the comment another instance wrote for the same intent.
 *
 * <p>Matching is on the earliest comment carrying the identity: it is the one
 * every later re-drive already converged on, so a thread that somehow holds two
 * (a duplicate posted before this contract) converges rather than alternating.
 *
 * <p>Failures surface as {@link GithubStateWriteException} — a {@code
 * TrackerUnavailableException}, so a bounded terminal-write retry consumes them
 * like any other outage (FR18) rather than treating a failed upsert as terminal.
 *
 * <p>Implements FR11, UX3 of harden-task-branch-contract.
 */
// Not a record: this is a behavior-bearing write service (a collaborator holding an HTTP client,
// not immutable data), kept as a plain final class for parity with its documented siblings
// GithubStateWrites / GithubCorrespondence.
public final class GithubCommentUpsert {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GithubHttpClient httpClient;
    private final GithubCommentThread thread;

    public GithubCommentUpsert(GithubHttpClient httpClient) {
        this.httpClient = httpClient;
        this.thread = new GithubCommentThread(httpClient);
    }

    /**
     * Writes {@code body} as the comment {@code identity} names on the issue
     * {@code id} names, updating an existing match in place or posting a new
     * comment when there is none.
     *
     * @param id the task whose issue thread carries the comment
     * @param identity the content identity to find the comment by; the caller
     *     is responsible for having stamped it into {@code body}
     * @param body the full comment body, structural marker line included
     * @return the GitHub comment id the write landed on
     */
    public long upsert(GithubTaskId id, GithubCommentIdentity identity, String body) {
        Optional<Long> existing = find(id, identity);
        return existing.map(commentId -> patch(id, commentId, body)).orElseGet(() -> post(id, body));
    }

    /**
     * The id of the earliest comment on {@code id}'s thread carrying {@code
     * identity}, or empty when the intent has never been written.
     *
     * @param id the task whose issue thread to search
     * @param identity the content identity to match
     * @return the matching comment id, or empty
     */
    private Optional<Long> find(GithubTaskId id, GithubCommentIdentity identity) {
        List<GithubCommentThread.RawComment> comments;
        try {
            comments = thread.fetchAll(id.owner(), id.repo(), id.issueNumber());
        } catch (GithubFeedQueryException e) {
            // The find is half of a write, so its failure is a write failure: reported as a
            // retryable tracker outage (FR18) rather than as the read-side feed exception, which
            // no terminal-write retry budget consumes.
            throw new GithubStateWriteException("Failed to read the thread of %s/%s#%d before writing a factory comment"
                    .formatted(id.owner(), id.repo(), id.issueNumber()));
        }
        for (GithubCommentThread.RawComment comment : comments) {
            Optional<ParsedMarker> marker = GithubMarker.parse(comment.body());
            if (marker.isPresent() && identity.equals(marker.get().identity())) {
                return Optional.of(comment.id());
            }
        }
        return Optional.empty();
    }

    private long post(GithubTaskId id, String body) {
        String path = "/repos/%s/%s/issues/%d/comments".formatted(id.owner(), id.repo(), id.issueNumber());
        HttpRequest.Builder request = httpClient
                .newRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GithubCommentBody.toJson(body)));
        return readCommentId(send(request, "post", id), id);
    }

    private long patch(GithubTaskId id, long commentId, String body) {
        String path = "/repos/%s/%s/issues/comments/%d".formatted(id.owner(), id.repo(), commentId);
        HttpRequest.Builder request = httpClient
                .newRequest(path)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(GithubCommentBody.toJson(body)));
        send(request, "update", id);
        return commentId;
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String verb, GithubTaskId id) {
        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() / 100 != 2) {
            throw new GithubStateWriteException("Failed to %s factory comment on %s/%s#%d: HTTP %d"
                    .formatted(verb, id.owner(), id.repo(), id.issueNumber(), response.statusCode()));
        }
        return response;
    }

    private static long readCommentId(HttpResponse<String> response, GithubTaskId id) {
        try {
            JsonNode node = MAPPER.readTree(response.body());
            return node.get("id").asLong();
        } catch (JsonProcessingException e) {
            throw new GithubStateWriteException("Failed to parse created comment response for %s/%s#%d"
                    .formatted(id.owner(), id.repo(), id.issueNumber()));
        }
    }
}
