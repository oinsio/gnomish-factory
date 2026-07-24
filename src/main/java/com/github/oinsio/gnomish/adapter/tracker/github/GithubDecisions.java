package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.app.port.tracker.HumanReply;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Implements {@code Tracker.collectDecisions} and {@code
 * Tracker.acknowledgeDecision} for the GitHub adapter (design D9, D13;
 * tracker-port spec "Decision collection anchored to the last ack"):
 * {@code collectDecisions} finds the latest {@code ack}-kind structural
 * marker and returns every comment after it, in posting order, that is NOT
 * itself a recognizable {@code gnomish} structural marker (a plain operator
 * reply); {@code acknowledgeDecision} posts an {@code ack}-kind structural
 * comment naming the decision text, which becomes the new anchor.
 *
 * <p>No ack anywhere on the thread anchors at the start — every non-marker
 * comment counts, matching {@code fetchTask}'s "boundary marker, whichever
 * is newest" idea (design D13) applied to the {@code ack} kind specifically,
 * since {@link GithubCommentBoundary} only recognizes {@code claim}/{@code
 * abort} as boundaries pre-4.14.
 *
 * <p>Implements FR12 of add-tracker-port.
 */
public final class GithubDecisions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GithubHttpClient httpClient;
    private final GithubCommentThread commentThread;
    private final String instanceId;

    public GithubDecisions(GithubHttpClient httpClient, String instanceId) {
        this.httpClient = httpClient;
        this.commentThread = new GithubCommentThread(httpClient);
        this.instanceId = instanceId;
    }

    /** Implements {@code Tracker.collectDecisions} for GitHub (FR12). */
    public List<HumanReply> collectDecisions(TaskRef ref) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        List<GithubCommentThread.RawComment> comments = commentThread.fetchAll(id.owner(), id.repo(), id.issueNumber());

        int fromIndex = latestAckIndex(comments).map(i -> i + 1).orElse(0);
        List<HumanReply> replies = new ArrayList<>();
        for (int i = fromIndex; i < comments.size(); i++) {
            GithubCommentThread.RawComment comment = comments.get(i);
            if (GithubMarker.parse(comment.body()).isEmpty()) {
                replies.add(new HumanReply(comment.body(), comment.createdAt()));
            }
        }
        return List.copyOf(replies);
    }

    /** Implements {@code Tracker.acknowledgeDecision} for GitHub (FR12, UX3). */
    public void acknowledgeDecision(TaskRef ref, String decisionText) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        String body = GithubMarker.render(
                GithubMarkerKind.ACK, instanceId, Instant.now(), "🤖 gnomish: acting on decision: " + decisionText);
        String path = "/repos/%s/%s/issues/%d/comments".formatted(id.owner(), id.repo(), id.issueNumber());
        HttpRequest.Builder request = httpClient
                .newRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toCommentBodyJson(body)));

        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() / 100 != 2) {
            throw new GithubFeedQueryException("Failed to post ack comment on %s/%s#%d: HTTP %d"
                    .formatted(id.owner(), id.repo(), id.issueNumber(), response.statusCode()));
        }
    }

    /** Returns the index (in comment order) of the latest ACK-kind marker, or empty if none is present. */
    private static Optional<Integer> latestAckIndex(List<GithubCommentThread.RawComment> comments) {
        Integer index = null;
        for (int i = 0; i < comments.size(); i++) {
            boolean isAck = GithubMarker.parse(comments.get(i).body())
                    .filter(marker -> marker.kind() == GithubMarkerKind.ACK)
                    .isPresent();
            if (isAck) {
                index = i;
            }
        }
        return Optional.ofNullable(index);
    }

    private static String toCommentBodyJson(String body) {
        try {
            return MAPPER.writeValueAsString(new CommentBody(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ack comment request body", e);
        }
    }

    private record CommentBody(String body) {}
}
