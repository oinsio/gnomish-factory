package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The one renderer every factory-authored tracker comment is written through
 * (FR11, FR13, UX3 of harden-task-branch-contract): it resolves the tenure this
 * instance holds on the task, stamps that claim epoch into the marker, derives
 * the write's content identity, and hands the body to {@link
 * GithubCommentUpsert}. Every {@link GithubMarkerKind} except the claim comes
 * through here — abort, ack, note, park, finish, progress, stale-claim removal and
 * index repair — so no ordinary write path posts blind and no second place decides
 * how a marker is stamped. The one exception is deliberate: the claim marker mints
 * the epoch this writer stamps, so {@link GithubClaimLease} posts it raw (the
 * find-then-upsert fused into its verify-read) and {@link GithubHeartbeat} patches
 * it in place — its own comment id is its identity.
 *
 * <h2>How a write scopes its identity</h2>
 *
 * The identity's intent is {@code <kind>@<scope>}. The scope decides which
 * writes are <em>the same comment</em> and which are new ones, and it is chosen
 * in this order:
 *
 * <ol>
 *   <li><b>An explicit scope</b>, for the two writes not made under the
 *       writer's own tenure: the {@code claim} (which mints the epoch, so it
 *       cannot be scoped by it — see {@link GithubClaimLease}) and the
 *       stale-claim removal (which acts on someone else's tenure, and scopes by
 *       the removed claim's epoch).
 *   <li><b>The tenure's claim epoch</b>, for every ordinary write: a crash-retry
 *       of the same park, finish, abort, ack, or progress re-drives the same
 *       identity and updates its comment in place (UX3), while the next tenure's
 *       write carries a different identity and lands as a new comment.
 *   <li><b>A digest of the human text</b>, for the claimless correspondence
 *       paths — {@code postNote} after the claim was already dropped, and
 *       {@code declineFinished}, which never holds one — where the text itself
 *       is the occurrence.
 * </ol>
 *
 * <p>Scoping by tenure rather than by task is load-bearing, not cosmetic. Four
 * kinds — abort, park, finish, stale-claim removal — are claim boundaries, and
 * {@link GithubClaimComment#isBoundary} anchors the lease on the latest one
 * <em>by position in the thread</em>. Updating a comment in place does not move
 * it, so a task-scoped park would edit the first park's comment and leave the
 * boundary frozen at a position later claims have already passed. Per-tenure
 * identities keep every boundary where its occurrence actually happened. The
 * same scoping keeps the abort count honest: at most one abort ends a tenure,
 * so counting abort comments still counts aborts.
 *
 * <p>Implements FR11, FR13, UX3 of harden-task-branch-contract.
 */
public final class GithubMarkerWriter {

    private final GithubCommentUpsert upsert;
    private final ClaimEpochSource epochs;
    private final String instanceId;

    /**
     * @param upsert the find-then-upsert primitive every write lands through
     * @param epochs this instance's tenure record — {@link ClaimEpochSource#NONE}
     *     for a path that never claims
     * @param instanceId the identifier of this factory instance, recorded in every marker
     */
    public GithubMarkerWriter(GithubCommentUpsert upsert, ClaimEpochSource epochs, String instanceId) {
        this.upsert = upsert;
        this.epochs = epochs;
        this.instanceId = instanceId;
    }

    /** The instance identifier this writer stamps into its markers. */
    public String instanceId() {
        return instanceId;
    }

    /**
     * Writes a marker scoped by the tenure this instance holds on the task (the
     * ordinary case), falling back to a digest of {@code humanText} when it
     * holds none.
     *
     * @param id the task whose issue thread carries the comment
     * @param kind the marker kind
     * @param humanText the human-readable text rendered below the structural line
     * @param reason the park reason's wire value, or {@code null} for every other kind
     * @return the GitHub comment id the write landed on
     */
    public long write(GithubTaskId id, GithubMarkerKind kind, String humanText, @Nullable String reason) {
        Optional<ClaimEpoch> tenure = epochs.epochFor(id.canonicalId());
        String scope = tenure.map(epoch -> Long.toString(epoch.token())).orElseGet(() -> digestOf(humanText));
        return write(
                id,
                new GithubMarkerWrite(kind, scope, humanText, reason, tenure.orElse(null), instanceId, Instant.now()));
    }

    /**
     * Writes a marker under an explicitly named scope and author — the stale-claim
     * removal (see the class Javadoc), the index repair, plus {@code recordAbort}
     * and the decision ack, whose scope or author is the caller's fact rather than
     * this writer's tenure.
     *
     * @param marker the write's full content (see {@link GithubMarkerWrite})
     * @return the GitHub comment id the write landed on
     */
    public long write(GithubTaskId id, GithubMarkerWrite marker) {
        GithubCommentIdentity identity =
                GithubCommentIdentity.of(id, marker.kind().wireValue() + "@" + marker.scope());
        String body = GithubMarker.render(
                marker.kind(),
                marker.author(),
                marker.at(),
                marker.humanText(),
                marker.reason(),
                identity,
                marker.epoch());
        return upsert.upsert(id, identity, body);
    }

    /**
     * The tenure this instance holds on {@code id}, for the callers that need
     * the epoch itself rather than only a stamped marker.
     *
     * @param id the task to look up
     * @return the tenure's epoch, or empty when this instance holds no claim
     */
    public Optional<ClaimEpoch> tenureOf(GithubTaskId id) {
        return epochs.epochFor(id.canonicalId());
    }

    /**
     * A short, stable digest of a claimless write's own text — its occurrence
     * key. Only the digest reaches the wire, so nothing readable from the text
     * is added to the marker beyond what the human line already shows (NFR-S1).
     */
    private static String digestOf(String humanText) {
        return Integer.toHexString(humanText.hashCode());
    }
}
