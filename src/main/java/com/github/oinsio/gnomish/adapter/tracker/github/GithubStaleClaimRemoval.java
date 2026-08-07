package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Optional;

/**
 * Implements {@code Tracker.removeStaleClaim} for the GitHub adapter (design D5,
 * D12, github-tracker spec "Stale-claim removal physics"): a version-guarded
 * reap that returns a dead holder's task to circulation without ever claiming it
 * for the caller.
 *
 * <p>The pre-action re-check reads the thread with a DIRECT list-comments GET —
 * never the conditional-request cache: the caller's {@code observedVersion} came
 * from a possibly-cached {@code listOpen}, so the current truth must be re-read
 * fresh here. The resolved claim's {@code (comment id, updated_at)} is compared
 * against {@code observedVersion}; any difference — the claim was beaten, already
 * removed, or replaced — is a safe no-op returning {@link
 * RemoveStaleClaimResult.Mismatch} (the live version, or {@code null} when the
 * claim is already gone), which is what makes concurrent removals converge
 * (NFR-R2). A {@code 404} on the re-read itself (the issue is gone) is the same
 * {@code Mismatch(null)} no-op — the claim vanished with its task, never an
 * infrastructure failure.
 *
 * <p>On a match it acts in order: (a) POST the {@code stale-claim-removed}
 * boundary marker FIRST — it names the dead holder, the removed comment id, the
 * observed version, and the time in its human-readable line, and anchors the next
 * lease round's verify-read like any other boundary — so the boundary exists even
 * if a later step fails; (b) DELETE the dead claim comment (all instances share
 * one token, so deletion is possible), tolerating a {@code 404} from a racing
 * remover that already deleted it; (c) flip the working label back to ready via
 * {@link GithubLabelOps#transition} (idempotent-in-effect: {@code removeLabel}
 * treats 404 as success, re-adding ready is harmless). Both a network failure and a
 * persistent 5xx are retried inside {@link GithubHttpClient} (NFR-R2), but surface
 * differently once retries are exhausted: a network failure propagates as an
 * infrastructure {@link com.github.oinsio.gnomish.adapter.github.GithubHttpException},
 * whereas an exhausted 5xx is returned as a
 * non-2xx response and throws a {@link GithubStaleClaimException} from the failing step.
 *
 * <p>Implements FR4, FR5 of add-claim-heartbeat.
 */
// Not a record: this is a behavior-bearing removal service (a collaborator holding an HTTP client,
// label ops and this instance's id, not immutable data), kept as a plain final class for parity
// with its documented siblings GithubClaimLease / GithubStateWrites / GithubHeartbeat.
@SuppressWarnings("ClassCanBeRecord")
public final class GithubStaleClaimRemoval {

    private final GithubHttpClient httpClient;
    private final GithubLabelOps labelOps;
    private final String instanceId;
    private final String workingLabel;
    private final String readyLabel;

    public GithubStaleClaimRemoval(
            GithubHttpClient httpClient,
            GithubLabelOps labelOps,
            String instanceId,
            String workingLabel,
            String readyLabel) {
        this.httpClient = httpClient;
        this.labelOps = labelOps;
        this.instanceId = instanceId;
        this.workingLabel = workingLabel;
        this.readyLabel = readyLabel;
    }

    /** Implements {@code Tracker.removeStaleClaim} for GitHub (FR4, FR5). */
    public RemoveStaleClaimResult removeStaleClaim(TaskRef ref, ClaimVersion observedVersion) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        Optional<GithubClaimComment.Candidate> live = resolveLiveClaim(id);
        if (live.isEmpty()) {
            return new RemoveStaleClaimResult.Mismatch(null);
        }
        GithubClaimComment.Candidate claim = live.get();
        ClaimVersion current = new ClaimVersion(Long.toString(claim.id()), claim.updatedAt());
        if (!current.equals(observedVersion)) {
            return new RemoveStaleClaimResult.Mismatch(current);
        }
        postRemovalMarker(id, claim, observedVersion);
        deleteClaimComment(id, claim.id());
        labelOps.transition(id.owner(), id.repo(), id.issueNumber(), workingLabel, readyLabel);
        return new RemoveStaleClaimResult.Removed();
    }

    /**
     * Freshly re-reads and resolves the live claim comment, or empty when there is nothing to
     * remove — folding into the {@link RemoveStaleClaimResult.Mismatch}{@code (null)} no-op above.
     * Empty covers two already-gone shapes: a thread whose claim comment is already deleted (a
     * racing remover converged), and a {@code 404} on the re-read itself — the issue is gone, so
     * the claim is gone too. Both converge without error (NFR-R2). Only a NON-404 non-2xx (403, an
     * exhausted 5xx) is an infrastructure failure worth throwing.
     */
    private Optional<GithubClaimComment.Candidate> resolveLiveClaim(GithubTaskId id) {
        String path = "/repos/%s/%s/issues/%d/comments?per_page=100".formatted(id.owner(), id.repo(), id.issueNumber());
        HttpResponse<String> response =
                httpClient.send(httpClient.newRequest(path).GET());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            throw new GithubStaleClaimException(
                    "Failed to re-read comments while removing a stale claim on %s/%s#%d: HTTP %d"
                            .formatted(id.owner(), id.repo(), id.issueNumber(), response.statusCode()));
        }
        return GithubClaimComment.resolve(GithubClaimComment.parse(response.body()));
    }

    private void postRemovalMarker(GithubTaskId id, GithubClaimComment.Candidate claim, ClaimVersion observedVersion) {
        String deadHolder = claim.marker().instance();
        String human = "🤖 gnomish: stale claim removed — dead holder %s, removed comment %d, observed version %s@%s"
                .formatted(deadHolder, claim.id(), observedVersion.markerId(), observedVersion.updatedAt());
        String body = GithubMarker.render(GithubMarkerKind.STALE_CLAIM_REMOVED, instanceId, Instant.now(), human);
        String path = "/repos/%s/%s/issues/%d/comments".formatted(id.owner(), id.repo(), id.issueNumber());
        HttpRequest.Builder request = httpClient
                .newRequest(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GithubCommentBody.toJson(body)));
        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() / 100 != 2) {
            throw new GithubStaleClaimException("Failed to post stale-claim-removed marker on %s/%s#%d: HTTP %d"
                    .formatted(id.owner(), id.repo(), id.issueNumber(), response.statusCode()));
        }
    }

    private void deleteClaimComment(GithubTaskId id, long commentId) {
        String path = "/repos/%s/%s/issues/comments/%d".formatted(id.owner(), id.repo(), commentId);
        HttpResponse<String> response =
                httpClient.send(httpClient.newRequest(path).DELETE());
        // A 404 means a racing remover already deleted this comment — a harmless
        // convergence, not a failure (spec "Racing removals converge").
        if (response.statusCode() == 404 || response.statusCode() / 100 == 2) {
            return;
        }
        throw new GithubStaleClaimException("Failed to delete dead claim comment %d on %s/%s: HTTP %d"
                .formatted(commentId, id.owner(), id.repo(), response.statusCode()));
    }
}
