package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Implements {@code Tracker.claim} for the GitHub adapter as a lease (design
 * D13, github-tracker spec "Lease-pattern claim decided by earliest comment
 * id"): point-add the working label / remove the ready label, post a
 * structural claim comment, then re-read claim comments since the newest
 * boundary marker and treat the earliest GitHub comment id as the winner.
 * The loser deletes its own claim comment, leaves labels as they stand (the
 * winner is racing through the same label calls), and reports {@code
 * Held(winner)}.
 *
 * <p>This class runs its own list-comments call and pairs each raw comment's
 * {@code id} with its parsed marker locally, rather than extending the
 * shared {@link ParsedMarker}/{@link GithubCommentParser}: neither {@link
 * GithubAbortFactsReader} nor {@link GithubTaskFetcher} needs a comment id,
 * so it stays local to the one caller that cares about GitHub's total order.
 *
 * <p>Post-succeeded-but-verify-fails (design D13, task 4.12): if the
 * verify-read still fails once {@code GithubHttpClient}'s own retries are
 * exhausted ({@link GithubHttpException}) or on a non-2xx response ({@link
 * GithubClaimException}), {@code claim} best-effort deletes its own claim
 * comment and lets the failure propagate. {@link Tracker} has no infra-
 * failure variant of {@link ClaimResult}, so — per the port's convention of
 * signalling infra failure by throwing — this never returns a result here.
 *
 * <p>Implements FR6, NFR-R1 of add-tracker-port.
 */
// Not a record: this is a behavior-bearing claim service (a collaborator holding an HttpClient and
// label ops, not immutable data), kept as a plain final class for parity with its documented
// siblings GithubTaskFetcher / GithubStateWrites / GithubLabelOps.
@SuppressWarnings("ClassCanBeRecord")
public final class GithubClaimLease {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GithubHttpClient httpClient;
    private final GithubLabelOps labelOps;
    private final String readyLabel;
    private final String workingLabel;

    public GithubClaimLease(
            GithubHttpClient httpClient, GithubLabelOps labelOps, String readyLabel, String workingLabel) {
        this.httpClient = httpClient;
        this.labelOps = labelOps;
        this.readyLabel = readyLabel;
        this.workingLabel = workingLabel;
    }

    /** Implements {@code Tracker.claim} for GitHub (FR6, NFR-R1). */
    public ClaimResult claim(TaskRef ref, String instanceId) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        labelOps.transition(id.owner(), id.repo(), id.issueNumber(), readyLabel, workingLabel);

        long ownCommentId = postClaimComment(id, instanceId);
        List<CommentAndMarker> comments = listCommentsOrCleanUp(id, ownCommentId);

        int fromIndex = latestBoundaryIndex(comments).map(i -> i + 1).orElse(0);
        long winnerId = ownCommentId;
        String winnerInstance = instanceId;
        for (int i = fromIndex; i < comments.size(); i++) {
            CommentAndMarker candidate = comments.get(i);
            if (candidate.marker().kind() != GithubMarkerKind.CLAIM) {
                continue;
            }
            if (isEarlier(candidate.id(), winnerId)) {
                winnerId = candidate.id();
                winnerInstance = candidate.marker().instance();
            }
        }

        if (winnerId == ownCommentId) {
            return new ClaimResult.Acquired();
        }
        deleteComment(id, ownCommentId);
        return new ClaimResult.Held(winnerInstance);
    }

    // PIT M4 documented exception (build.gradle has the full rationale style): @DoNotMutate
    // because `<` vs `<=` (ConditionalsBoundaryMutator) is a genuine equivalent mutant here —
    // GitHub comment ids are server-assigned and always distinct within one issue, so `candidateId
    // <= winnerId` can only ever disagree with `candidateId < winnerId` at candidateId ==
    // winnerId, a value this comparison never receives (a candidate is never compared against its
    // own id). Covered at the ordinary test level by GithubClaimLeaseSpec's claim-race scenario.
    @DoNotMutate
    private static boolean isEarlier(long candidateId, long winnerId) {
        return candidateId < winnerId;
    }

    private long postClaimComment(GithubTaskId id, String instanceId) {
        String body = GithubMarker.render(
                GithubMarkerKind.CLAIM, instanceId, Instant.now(), "🤖 gnomish: claimed by " + instanceId);
        String path = "/repos/%s/%s/issues/%d/comments".formatted(id.owner(), id.repo(), id.issueNumber());
        HttpRequest.Builder request = httpClient
                .newRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GithubCommentBody.toJson(body)));

        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() / 100 != 2) {
            throw new GithubClaimException("Failed to post claim comment on %s/%s#%d: HTTP %d"
                    .formatted(id.owner(), id.repo(), id.issueNumber(), response.statusCode()));
        }
        return readCommentId(response.body(), id);
    }

    /**
     * Runs the verify-read (step 3, design D13); on an infrastructure
     * failure ({@link GithubHttpException} or {@link GithubClaimException})
     * best-effort deletes {@code ownCommentId} first, then rethrows.
     */
    private List<CommentAndMarker> listCommentsOrCleanUp(GithubTaskId id, long ownCommentId) {
        try {
            return listComments(id);
        } catch (GithubHttpException | GithubClaimException e) {
            deleteComment(id, ownCommentId);
            throw e;
        }
    }

    private List<CommentAndMarker> listComments(GithubTaskId id) {
        String path = "/repos/%s/%s/issues/%d/comments?per_page=100".formatted(id.owner(), id.repo(), id.issueNumber());
        HttpRequest.Builder request = httpClient.newRequest(path).GET();
        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() / 100 != 2) {
            throw new GithubClaimException("Failed to list comments for %s/%s#%d: HTTP %d"
                    .formatted(id.owner(), id.repo(), id.issueNumber(), response.statusCode()));
        }
        return parseCommentsWithIds(response.body(), id);
    }

    private void deleteComment(GithubTaskId id, long commentId) {
        String path = "/repos/%s/%s/issues/comments/%d".formatted(id.owner(), id.repo(), commentId);
        HttpRequest.Builder request = httpClient.newRequest(path).DELETE();
        // Best-effort per design D13: a delete failure, including exhausted
        // retries surfaced as GithubHttpException, never masks the caller's
        // own outcome (a Held result, or the verify-read failure above).
        try {
            httpClient.send(request);
        } catch (GithubHttpException ignored) {
            // Swallowed: see above.
        }
    }

    /**
     * Returns the index of the latest (highest comment-order) boundary marker
     * among {@code comments}, or empty if none is present. A boundary is any
     * marker that ended the prior working session and so voids every claim
     * posted before it (design D13: "since the newest boundary marker —
     * release/park/abort/finish — whichever is newest"): an {@code abort}
     * (task returned to {@code Ready}) or a {@code report} — {@link
     * GithubStateWrites} posts both {@code park} and {@code finish} as
     * REPORT-kind markers, the only REPORT markers this adapter writes.
     * ({@code release} posts no marker on GitHub — it is a documented no-op,
     * design D2 — so it never appears here.)
     *
     * <p>Without this, a stale CLAIM left by a holder that has since parked
     * or finished the task always wins by earliest id, so a task returned to
     * {@code Ready} after a park could never be re-claimed until an abort
     * happened to reset the window. GitHub's own comments-listing order
     * already reflects the server-side total order (design D13), so this
     * scans by list position rather than by parsed timestamp — immune to
     * clock skew between racing instances.
     */
    private static Optional<Integer> latestBoundaryIndex(List<CommentAndMarker> comments) {
        Integer index = null;
        for (int i = 0; i < comments.size(); i++) {
            GithubMarkerKind kind = comments.get(i).marker().kind();
            if (kind == GithubMarkerKind.ABORT || kind == GithubMarkerKind.REPORT) {
                index = i;
            }
        }
        return Optional.ofNullable(index);
    }

    private static List<CommentAndMarker> parseCommentsWithIds(String commentsJson, GithubTaskId id) {
        try {
            JsonNode array = MAPPER.readTree(commentsJson);
            List<CommentAndMarker> comments = new ArrayList<>();
            for (JsonNode comment : array) {
                GithubMarker.parse(comment.get("body").asText())
                        .ifPresent(marker -> comments.add(
                                new CommentAndMarker(comment.get("id").asLong(), marker)));
            }
            return List.copyOf(comments);
        } catch (JsonProcessingException e) {
            throw new GithubClaimException("Failed to parse comments response for %s/%s#%d"
                    .formatted(id.owner(), id.repo(), id.issueNumber()));
        }
    }

    private static long readCommentId(String createdCommentJson, GithubTaskId id) {
        try {
            JsonNode node = MAPPER.readTree(createdCommentJson);
            return node.get("id").asLong();
        } catch (JsonProcessingException e) {
            throw new GithubClaimException("Failed to parse created claim comment response for %s/%s#%d"
                    .formatted(id.owner(), id.repo(), id.issueNumber()));
        }
    }

    /** Pairs a raw GitHub comment id with its parsed structural marker, local to this class (task 4.11). */
    private record CommentAndMarker(long id, ParsedMarker marker) {}
}
