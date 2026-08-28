package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient;
import com.github.oinsio.gnomish.adapter.github.GithubHttpException;
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
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
 * <p>Content identity and the fused find (FR11, UX3 of harden-task-branch-contract): the claim
 * marker carries the identity {@code claim@<instanceId>}, but its find-then-upsert is fused into
 * the verify-read this method already performs rather than delegated to {@link
 * GithubCommentUpsert}. Two reasons, both structural. The claim must obtain a <em>fresh</em>
 * comment id to race with and to become the tenure's epoch (design D6), so it cannot begin by
 * updating an existing comment; and its scope is the post-boundary window, not the whole thread —
 * a claim comment from a tenure that has since parked, aborted, or been reaped is deliberately
 * <em>not</em> the same comment, which is what lets a reclaim mint a strictly greater epoch
 * (FR13). So the verify-read is the find: if this instance's own identity already appears in the
 * window — a claim comment left by an attempt that died before verifying — that earlier comment
 * legitimately holds the lease by earliest id, this attempt adopts it as its epoch, and the
 * duplicate this attempt just posted is deleted. The thread never accumulates duplicate claims,
 * and no extra API read is paid for the guarantee.
 *
 * <p>The claim marker carries no {@code epoch} stamp: its own comment id <em>is</em> the epoch,
 * and it is not assigned until the comment exists.
 *
 * <p>Write order is contract (FR12 of harden-task-branch-contract), and it follows the
 * sweep-universe rule: the working label goes on FIRST — it is the write that admits the task into
 * the sweep's own query — then the claim comment, then the verify-read. Every kill window of the
 * sequence therefore freezes a state the sweep enumerates and no window is authoritative in the
 * race: a kill before the comment leaves the working label with no claim footprint, the {@code
 * ClaimPending} shape the reaper rolls back after its grace; a kill after it leaves an ordinary
 * dead-holder {@code Claimed} the TTL reaps. The race-winning write is the last one, so a frozen
 * window never wins a lease it did not verify.
 *
 * <p>Implements FR6, NFR-R1 of add-tracker-port; FR11, FR12, FR13, UX3 of
 * harden-task-branch-contract.
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

    /** Implements {@code Tracker.claim} for GitHub (FR6, NFR-R1, FR12). */
    public ClaimResult claim(TaskRef ref, String instanceId) {
        GithubTaskId id = GithubTaskId.parse(ref.id());
        GithubCommentIdentity identity = claimIdentityOf(id, instanceId);
        // Step 1 of the sweep-universe order (FR12): the working label first, so every later kill
        // window freezes a task the sweep's own listing enumerates.
        labelOps.transition(id.owner(), id.repo(), id.issueNumber(), readyLabel, workingLabel);

        long ownCommentId = postClaimComment(id, instanceId, identity);
        GithubClaimWindow window = listCommentsOrCleanUp(id, ownCommentId);

        Optional<GithubClaimWindow.Entry> beatenBy = window.winnerAgainst(ownCommentId);
        if (beatenBy.isPresent() && !identity.equals(beatenBy.get().marker().identity())) {
            deleteComment(id, ownCommentId);
            return new ClaimResult.Held(beatenBy.get().marker().instance());
        }
        // The winning comment's id IS the tenure's epoch (design D6, FR13 of
        // harden-task-branch-contract): GitHub assigns comment ids in increasing order, so a
        // reclaim after any boundary necessarily outranks the tenure it replaced. The winner may
        // be this instance's own earlier claim comment, left by an attempt that died before
        // verifying — it holds the lease, and every other comment of this identity in the window
        // is that attempt's duplicate (FR11, UX3).
        long winnerId = beatenBy.map(GithubClaimWindow.Entry::id).orElse(ownCommentId);
        window.duplicatesOf(identity, winnerId).forEach(duplicate -> deleteComment(id, duplicate));
        return new ClaimResult.Acquired(new ClaimEpoch(winnerId));
    }

    /** The content identity every claim comment of {@code instanceId} on this task carries (FR11). */
    private static GithubCommentIdentity claimIdentityOf(GithubTaskId id, String instanceId) {
        return GithubCommentIdentity.of(id, GithubMarkerKind.CLAIM.wireValue() + "@" + instanceId);
    }

    private long postClaimComment(GithubTaskId id, String instanceId, GithubCommentIdentity identity) {
        String body = GithubMarker.render(
                GithubMarkerKind.CLAIM,
                instanceId,
                Instant.now(),
                "🤖 gnomish: claimed by " + instanceId,
                null,
                identity,
                null);
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
    private GithubClaimWindow listCommentsOrCleanUp(GithubTaskId id, long ownCommentId) {
        try {
            return listComments(id);
        } catch (GithubHttpException | GithubClaimException e) {
            deleteComment(id, ownCommentId);
            throw e;
        }
    }

    private GithubClaimWindow listComments(GithubTaskId id) {
        String path = "/repos/%s/%s/issues/%d/comments?per_page=100".formatted(id.owner(), id.repo(), id.issueNumber());
        HttpRequest.Builder request = httpClient.newRequest(path).GET();
        HttpResponse<String> response = httpClient.send(request);
        if (response.statusCode() / 100 != 2) {
            throw new GithubClaimException("Failed to list comments for %s/%s#%d: HTTP %d"
                    .formatted(id.owner(), id.repo(), id.issueNumber(), response.statusCode()));
        }
        return GithubClaimWindow.of(response.body(), id);
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

    private static long readCommentId(String createdCommentJson, GithubTaskId id) {
        try {
            JsonNode node = MAPPER.readTree(createdCommentJson);
            return node.get("id").asLong();
        } catch (JsonProcessingException e) {
            throw new GithubClaimException("Failed to parse created claim comment response for %s/%s#%d"
                    .formatted(id.owner(), id.repo(), id.issueNumber()));
        }
    }
}
