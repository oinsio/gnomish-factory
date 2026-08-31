package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import com.github.oinsio.gnomish.app.port.tracker.HumanReply;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
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

    private final GithubCommentThread commentThread;
    private final GithubMarkerWriter markerWriter;

    public GithubDecisions(GithubHttpClient httpClient, GithubMarkerWriter markerWriter) {
        this.commentThread = new GithubCommentThread(httpClient);
        this.markerWriter = markerWriter;
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

    /**
     * Implements {@code Tracker.acknowledgeDecision} for GitHub (FR12, UX3), through the shared
     * marker writer (FR11).
     *
     * <p>The ack is scoped by the tenure <em>and</em> the decision it answers, not by the tenure
     * alone: the ack is the boundary {@link #latestAckIndex} anchors decision collection on, and a
     * tenure can answer more than one decision. Re-driving the same ack after a crash updates its
     * own comment (UX3); acknowledging a second decision posts a new one, so the boundary lands
     * where that acknowledgment actually happened and no answered decision is collected twice.
     */
    public void acknowledgeDecision(TaskRef ref, String decisionText) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        ClaimEpoch tenure = markerWriter.tenureOf(id).orElse(null);
        String scope = (tenure == null ? "none" : Long.toString(tenure.token())) + "."
                + Integer.toHexString(decisionText.hashCode());
        markerWriter.write(
                id,
                new GithubMarkerWrite(
                        GithubMarkerKind.ACK,
                        scope,
                        "🤖 gnomish: acting on decision: " + decisionText,
                        null,
                        tenure,
                        markerWriter.instanceId(),
                        Instant.now()));
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
}
