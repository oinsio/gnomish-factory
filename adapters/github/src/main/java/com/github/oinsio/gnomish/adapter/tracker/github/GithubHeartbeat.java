package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Implements {@code Tracker.heartbeat} for the GitHub adapter as an in-place edit
 * of the claim comment (add-claim-heartbeat design D1, github-tracker spec "Beat
 * is an in-place edit of the claim comment"): resolve the task's live claim
 * comment via the shared {@link GithubClaimComment} resolver, then PATCH that same
 * comment id — the lease anchor, which never changes — with a refreshed {@code
 * CLAIM} marker whose human line carries the progress payload. One beat is exactly
 * one write; the reported {@link ClaimVersion} is {@code (comment id, updated_at)}
 * read from the PATCH response.
 *
 * <p>Failure mapping (FR8): a {@code 404} on the PATCH — the comment was deleted by
 * a reaper or a takeover — is the {@link HeartbeatResult.ClaimGone} protocol signal,
 * as is an unresolvable claim comment (nothing to beat) and a {@code 404} on the comment
 * listing (the issue itself is gone — the strongest form of a lost claim). Both a network failure and a
 * persistent 5xx are retried inside {@link GithubHttpClient} (NFR-R2), but they surface
 * differently once retries are exhausted: a network failure propagates as an
 * infrastructure {@link com.github.oinsio.gnomish.adapter.github.GithubHttpException},
 * whereas an exhausted 5xx is returned as a
 * non-2xx response and — like any other non-404, non-2xx status — throws a {@link
 * GithubHeartbeatException} from the failing step. Either way the beat throws rather
 * than returning a lost-claim result, matching {@link GithubClaimLease}'s "signal infra
 * failure by throwing" convention.
 *
 * <p>The rendered marker matches {@link GithubClaimLease}'s claim-comment shape
 * exactly ({@code CLAIM} kind, this instance's id, {@code now}, and the same
 * {@code claim@<instanceId>} content identity, built by {@link
 * GithubClaimLease#claimIdentityOf}), so the anchor and every boundary-aware
 * re-read stay uniform across a claim's beats. Re-rendering the identity is what
 * keeps a beaten comment adoptable: the lease's find is content-identity based
 * (FR11 of harden-task-branch-contract), and a beat that dropped it would leave a
 * live claim the lease can no longer match against its own instance.
 *
 * <p>A beat writes only to a claim this instance still holds (FR7): a resolved
 * claim comment naming a different instance is the {@link
 * HeartbeatResult.ClaimGone} signal, not something to PATCH. A reaped zombie
 * whose beat overwrote the new holder's claim comment would rewrite the very
 * holder the zombie fence reads — {@code ClaimGuard} and the round-boundary
 * revocation check both decide "still ours" from that marker — inverting the
 * fence instead of tripping it.
 *
 * <p>Implements FR1, FR7, FR8 of add-claim-heartbeat; FR11 of
 * harden-task-branch-contract.
 */
// Not a record: this is a behavior-bearing beat service (a collaborator holding an HTTP client and
// this instance's id, not immutable data), kept as a plain final class for parity with its
// documented siblings GithubClaimLease / GithubStateWrites.
@SuppressWarnings("ClassCanBeRecord")
public final class GithubHeartbeat {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GithubHttpClient httpClient;
    private final String instanceId;

    public GithubHeartbeat(GithubHttpClient httpClient, String instanceId) {
        this.httpClient = httpClient;
        this.instanceId = instanceId;
    }

    /** Implements {@code Tracker.heartbeat} for GitHub (FR1, FR8). */
    public HeartbeatResult heartbeat(TaskRef ref, String progressPayload) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        // An explicit branch, not Optional.map(..).orElseGet(ClaimGone::new): map would launder a
        // null out of patchClaimComment into the very ClaimGone its own 404 branch returns, making
        // PIT's null-return mutant of that branch observationally equivalent (an unkillable
        // survivor). With the branch explicit, a null from the patch step reaches the caller.
        Optional<GithubClaimComment.Candidate> claim = resolveClaim(id);
        if (claim.isEmpty() || !instanceId.equals(claim.get().marker().instance())) {
            return new HeartbeatResult.ClaimGone();
        }
        return patchClaimComment(id, claim.get().id(), progressPayload);
    }

    /**
     * Resolves the live claim comment, or empty when there is nothing to beat — folding into the
     * {@link HeartbeatResult.ClaimGone} signal above. Empty covers two lost-claim shapes: a thread
     * that carries no live claim marker, and a {@code 404} on the listing itself — the issue is
     * gone, the strongest form of a lost claim, so it is the claim-gone signal, never mistaken for
     * an outage. Only a NON-404 non-2xx (403, an exhausted 5xx) is an infrastructure failure worth
     * throwing (FR8).
     */
    private Optional<GithubClaimComment.Candidate> resolveClaim(GithubTaskId id) {
        String path = "/repos/%s/%s/issues/%d/comments?per_page=100".formatted(id.owner(), id.repo(), id.issueNumber());
        HttpResponse<String> response =
                httpClient.send(httpClient.newRequest(path).GET());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            throw new GithubHeartbeatException("Failed to list comments while beating claim on %s/%s#%d: HTTP %d"
                    .formatted(id.owner(), id.repo(), id.issueNumber(), response.statusCode()));
        }
        return GithubClaimComment.resolve(GithubClaimComment.parse(response.body()));
    }

    private HeartbeatResult patchClaimComment(GithubTaskId id, long commentId, String progressPayload) {
        // The same body the claim itself was written with (FR11 of harden-task-branch-contract),
        // identity included: a beat refreshes the claim comment, it does not replace it with a
        // different kind of write, so the identity a reclaim adopts the comment by must survive the
        // edit. No epoch: a claim marker never carries one — its own comment id IS the epoch.
        String body = GithubMarker.render(
                GithubMarkerKind.CLAIM,
                instanceId,
                Instant.now(),
                progressPayload,
                null,
                GithubClaimLease.claimIdentityOf(id, instanceId),
                null);
        String path = "/repos/%s/%s/issues/comments/%d".formatted(id.owner(), id.repo(), commentId);
        HttpRequest.Builder request = httpClient
                .newRequest(path)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(GithubCommentBody.toJson(body)));

        HttpResponse<String> response = httpClient.send(request);
        int status = response.statusCode();
        if (status == 404) {
            return new HeartbeatResult.ClaimGone();
        }
        if (status / 100 != 2) {
            throw new GithubHeartbeatException("Failed to beat claim comment %d on %s/%s: HTTP %d"
                    .formatted(commentId, id.owner(), id.repo(), status));
        }
        return new HeartbeatResult.Beaten(GithubClaimComment.versionOf(commentId, readUpdatedAt(response.body(), id)));
    }

    private static Instant readUpdatedAt(String patchResponseJson, GithubTaskId id) {
        try {
            JsonNode node = MAPPER.readTree(patchResponseJson);
            JsonNode updatedAt = node.get("updated_at");
            if (updatedAt == null) {
                throw new GithubHeartbeatException(
                        "Beat response for %s/%s has no updated_at".formatted(id.owner(), id.repo()));
            }
            return Instant.parse(updatedAt.asText());
        } catch (JsonProcessingException | DateTimeParseException e) {
            throw new GithubHeartbeatException(
                    "Failed to parse beat response for %s/%s".formatted(id.owner(), id.repo()));
        }
    }
}
