package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves a task's live claim comment from its comment thread (add-claim-heartbeat
 * design D1/D5): the earliest-GitHub-comment-id {@code claim} marker posted after
 * the latest session-ending boundary marker — the same "earliest id since the
 * newest boundary" rule {@link GithubClaimLease} decides a lease race with, but
 * carrying the resolved comment's {@code id}, {@code updated_at}, and parsed
 * marker so a {@link com.github.oinsio.gnomish.app.port.tracker.ClaimVersion}
 * can be reported. {@code heartbeat} (task 3.1) PATCHes the resolved comment,
 * and {@code listOpen}/{@code removeStaleClaim} (tasks 3.2/3.3) will reuse this
 * one resolver rather than each re-deriving the winner.
 *
 * <p>This is pure over a parsed candidate list — it issues no HTTP itself, so a
 * caller lists the comments once (through the conditional-request cache where it
 * has one) and feeds the body to {@link #parse(String)}. It is the id-and-version
 * aware sibling of {@link GithubCommentBoundary}: that one answers "who holds the
 * task now" over {@code CLAIM}/{@code ABORT} with no ids (fetchTask), while this
 * one answers "which comment IS the claim anchor" over the lease boundary set —
 * two boundaries for two questions, kept separate as the codebase already does.
 *
 * <p>The boundary set is {@code ABORT}, {@code PARK}, and {@code FINISH} — the two
 * session-ending write paths {@link GithubStateWrites} posts, matching {@link
 * GithubClaimLease}'s lease boundary, which reuses {@link
 * #isBoundary(GithubMarkerKind)} rather than duplicating the set.
 *
 * <p>Implements FR1, FR5 of add-claim-heartbeat; enforce-finish-terminality
 * task 3.2 (the claim-boundary set replaced the retired {@code REPORT} kind
 * with {@code PARK} and {@code FINISH}).
 */
final class GithubClaimComment {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GithubClaimComment() {}

    /**
     * A comment carrying a {@code gnomish} structural marker, paired with the
     * facts a claim version needs: its GitHub comment {@code id}, its {@code
     * updated_at} instant, and the parsed {@code marker}. The resolved winner is
     * itself a {@code Candidate}.
     */
    record Candidate(long id, Instant updatedAt, ParsedMarker marker) {}

    /**
     * Parses a "List issue comments" response body into marker-bearing candidates,
     * in comment (posting) order, skipping any comment with no recognizable
     * {@code gnomish} marker (an operator's own reply).
     */
    static List<Candidate> parse(String commentsJson) {
        try {
            JsonNode array = MAPPER.readTree(commentsJson);
            List<Candidate> comments = new ArrayList<>();
            for (JsonNode comment : array) {
                GithubMarker.parse(comment.get("body").asText())
                        .ifPresent(marker -> comments.add(new Candidate(
                                comment.get("id").asLong(),
                                Instant.parse(comment.get("updated_at").asText()),
                                marker)));
            }
            return List.copyOf(comments);
        } catch (JsonProcessingException e) {
            throw new GithubClaimException("Failed to parse comments response while resolving the claim comment");
        }
    }

    /**
     * The port-level {@link ClaimVersion} of a claim comment: on GitHub one number answers both
     * questions the version asks — the comment id is the marker's identity AND, because GitHub
     * assigns comment ids in increasing order, the tenure's monotonic claim epoch (design D6, FR13
     * of harden-task-branch-contract). Minting both from the one number in one place is what keeps
     * them from ever disagreeing; the three callers that report a claim version — {@code
     * heartbeat}, {@code listOpen}, and {@code removeStaleClaim} — all come through here.
     *
     * @param commentId the claim comment's GitHub id
     * @param updatedAt the comment's last-update fact
     * @return the version to report over the port; never null
     */
    static ClaimVersion versionOf(long commentId, Instant updatedAt) {
        return new ClaimVersion(Long.toString(commentId), updatedAt, new ClaimEpoch(commentId));
    }

    /**
     * Returns the live claim comment among {@code comments}, or empty when none
     * exists (the claim was deleted or the latest boundary is not followed by a
     * claim). The winner is the earliest comment id among {@code CLAIM} markers
     * posted after the latest boundary marker.
     */
    static Optional<Candidate> resolve(List<Candidate> comments) {
        int fromIndex = latestBoundaryIndex(comments).map(i -> i + 1).orElse(0);
        Candidate winner = null;
        for (int i = fromIndex; i < comments.size(); i++) {
            Candidate candidate = comments.get(i);
            if (candidate.marker().kind() != GithubMarkerKind.CLAIM) {
                continue;
            }
            if (winner == null || isEarlier(candidate.id(), winner.id())) {
                winner = candidate;
            }
        }
        return Optional.ofNullable(winner);
    }

    private static Optional<Integer> latestBoundaryIndex(List<Candidate> comments) {
        Integer index = null;
        for (int i = 0; i < comments.size(); i++) {
            if (isBoundary(comments.get(i).marker().kind())) {
                index = i;
            }
        }
        return Optional.ofNullable(index);
    }

    /**
     * The session-ending boundary kinds that void every claim posted before them
     * (design D13's boundary list, extended by add-claim-heartbeat design D12).
     * {@code PARK} and {@code FINISH} both end a session the same way a plain
     * {@code REPORT} marker used to (enforce-finish-terminality task 3.1 split
     * the single {@code REPORT} kind into these two dedicated kinds; both still
     * count as boundaries here). A {@code STALE_CLAIM_REMOVED} marker — a
     * reaper's removal boundary — voids the claim it removed so the next lease
     * round's verify-read considers only claims posted after it (github-tracker
     * spec "Marker anchors the next lease round"), even in the case where the
     * dead claim comment's deletion did not land but the boundary marker did.
     *
     * <p>Package-private: {@code GithubClaimLease.latestBoundaryIndex} reuses
     * this exact set rather than duplicating it (the two boundary scans were
     * textually identical).
     */
    static boolean isBoundary(GithubMarkerKind kind) {
        return kind == GithubMarkerKind.ABORT
                || kind == GithubMarkerKind.PARK
                || kind == GithubMarkerKind.FINISH
                || kind == GithubMarkerKind.STALE_CLAIM_REMOVED;
    }

    // PIT M4 documented exception (build.gradle has the full rationale style): @DoNotMutate —
    // `<` vs `<=` (ConditionalsBoundaryMutator) is a genuine equivalent mutant here, exactly as in
    // GithubClaimLease#isEarlier: GitHub comment ids are server-assigned and distinct within one
    // issue, and a candidate is never compared against its own id (the winner is a different
    // comment), so candidateId == winnerId never occurs. Covered by GithubClaimCommentSpec's
    // earliest-id-wins scenario.
    @DoNotMutate
    private static boolean isEarlier(long candidateId, long winnerId) {
        return candidateId < winnerId;
    }
}
