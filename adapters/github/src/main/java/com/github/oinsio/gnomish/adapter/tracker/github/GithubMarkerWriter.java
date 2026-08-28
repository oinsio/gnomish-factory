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
 * GithubCommentUpsert}. All eight marker kinds — claim, abort, ack, note, park,
 * finish, progress, stale-claim removal — come through here, so no write path
 * posts blind and no second place decides how a marker is stamped.
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
        return write(id, kind, scope, humanText, reason, tenure.orElse(null), instanceId, Instant.now());
    }

    /**
     * Writes a marker under an explicitly named scope and author — the claim and
     * the stale-claim removal (see the class Javadoc), plus {@code recordAbort},
     * whose {@code AbortRecord} names the instance that actually aborted.
     *
     * @param scope the occurrence this write belongs to, appended to the kind
     * @param author the instance to record as the marker's author
     * @param epoch the tenure to stamp, or {@code null} to stamp none
     * @param at the marker's own timestamp — the recorded fact's time where the caller has one
     *     (an {@code AbortRecord}'s {@code at}, which the abort-facts fold reads back), not the
     *     moment the write happens to be re-driven
     * @return the GitHub comment id the write landed on
     */
    public long write(
            GithubTaskId id,
            GithubMarkerKind kind,
            String scope,
            String humanText,
            @Nullable String reason,
            @Nullable ClaimEpoch epoch,
            String author,
            Instant at) {
        GithubCommentIdentity identity = GithubCommentIdentity.of(id, kind.wireValue() + "@" + scope);
        String body = GithubMarker.render(kind, author, at, humanText, reason, identity, epoch);
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
