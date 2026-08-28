package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind;
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts;
import com.github.oinsio.gnomish.app.port.tracker.StateLabels;
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Turns one issue's parsed comment thread into the port's {@link TrackerFacts} — the adapter's
 * whole share of the tracker-shape work, which is reporting and never judging (design D16). Pure
 * over the candidates {@link GithubClaimComment#parse} already produced, so the two listings and
 * the index repair share one derivation instead of three.
 *
 * <p>The claim footprint follows the lease's own anchor rule: the live claim is the earliest claim
 * comment since the newest boundary ({@link GithubClaimComment#resolve}); with none, a thread that
 * still carries any claim marker is a dead footprint naming its last-known holder, and a thread
 * with no claim marker at all is no footprint. The boundary fact is scoped after the newest claim
 * comment on purpose: a boundary that ended an earlier tenure implies nothing about this one.
 *
 * <p>Implements FR19, FR12 of harden-task-branch-contract.
 */
final class GithubTrackerFacts {

    /**
     * The holder named on the legacy {@code Working} projection of an issue whose thread carries no
     * claim marker at all. That projection's holder is a non-null string by construction, so the
     * absence has to be spelled somehow; the claim facts alongside it carry the truth — an absent
     * footprint — and every classification reads those, never this placeholder. Reporting the
     * combination beats refusing it: an issue wearing the working label with no claim is the claim
     * sequence's own kill window, and a reader that throws on it hides the window (FR19).
     */
    static final String UNKNOWN_HOLDER = "unknown";

    private GithubTrackerFacts() {}

    /**
     * The fact triple for an issue wearing {@code labels} whose thread parsed to {@code comments}.
     *
     * @param labels the presence facts of the issue's labels; never null
     * @param comments the issue's parsed marker-bearing comments, in posting order; never null
     * @return the facts to report over the port; never null
     */
    static TrackerFacts of(StateLabels labels, List<GithubClaimComment.Candidate> comments) {
        return new TrackerFacts(labels, claim(comments), latestBoundary(comments));
    }

    /**
     * The claim footprint of a parsed thread: live, dead, or none.
     *
     * @param comments the issue's parsed marker-bearing comments, in posting order; never null
     * @return the footprint fact; never null
     */
    static ClaimFacts claim(List<GithubClaimComment.Candidate> comments) {
        Optional<GithubClaimComment.Candidate> live = GithubClaimComment.resolve(comments);
        if (live.isPresent()) {
            GithubClaimComment.Candidate claim = live.get();
            return new ClaimFacts.Live(
                    claim.marker().instance(), GithubClaimComment.versionOf(claim.id(), claim.updatedAt()));
        }
        return lastClaimHolder(comments).<ClaimFacts>map(ClaimFacts.Dead::new).orElseGet(ClaimFacts.None::new);
    }

    /**
     * The newest boundary marker posted after the newest claim comment, or {@code null} when the
     * newest claim is itself the last word.
     *
     * @param comments the issue's parsed marker-bearing comments, in posting order; never null
     * @return the boundary fact, or null
     */
    static @Nullable BoundaryKind latestBoundary(List<GithubClaimComment.Candidate> comments) {
        int lastClaim = -1;
        int lastBoundary = -1;
        for (int i = 0; i < comments.size(); i++) {
            GithubMarkerKind kind = comments.get(i).marker().kind();
            if (kind == GithubMarkerKind.CLAIM) {
                lastClaim = i;
            } else if (GithubClaimComment.isBoundary(kind)) {
                lastBoundary = i;
            }
        }
        if (lastBoundary < 0 || isAfter(lastClaim, lastBoundary)) {
            return null;
        }
        return boundaryOf(comments.get(lastBoundary).marker().kind());
    }

    /**
     * Whether the comment at {@code index} comes after the one at {@code other} in the thread.
     *
     * <p>PIT M4 documented exception: {@code @DoNotMutate} because {@code >} vs {@code >=}
     * (ConditionalsBoundaryMutator) is a provably equivalent mutant here — the two are positions of
     * comments of DIFFERENT kinds in one list, so they can never be equal, and the only value they
     * share ({@code -1}, "no such comment") is excluded by the caller's own guard. Kept as its own
     * method so the exemption costs one comparison and leaves the rest of the reader in the gate.
     * Covered by GithubOpenQuerySpec's boundary scenarios.
     */
    @DoNotMutate
    private static boolean isAfter(int index, int other) {
        return index > other;
    }

    /** The port kind of an adapter boundary marker; the four boundary kinds map one to one. */
    private static @Nullable BoundaryKind boundaryOf(GithubMarkerKind kind) {
        return switch (kind) {
            case ABORT -> BoundaryKind.ABORT;
            case PARK -> BoundaryKind.PARK;
            case FINISH -> BoundaryKind.FINISH;
            case STALE_CLAIM_REMOVED -> BoundaryKind.STALE_CLAIM_REMOVED;
            case CLAIM, ACK, NOTE, PROGRESS, INDEX_REPAIR -> null;
        };
    }

    /** The instance of the last claim marker still visible in the thread, or empty when none is. */
    private static Optional<String> lastClaimHolder(List<GithubClaimComment.Candidate> comments) {
        for (int i = comments.size() - 1; i >= 0; i--) {
            GithubClaimComment.Candidate candidate = comments.get(i);
            if (candidate.marker().kind() == GithubMarkerKind.CLAIM) {
                return Optional.of(candidate.marker().instance());
            }
        }
        return Optional.empty();
    }
}
