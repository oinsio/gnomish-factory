package com.github.oinsio.gnomish.adapter.tracker.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.DoNotMutate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The claim comments a lease race is decided among: everything posted after the newest boundary
 * marker on the issue thread (design D13). {@link GithubClaimLease} owns the HTTP and the label
 * calls; this class owns the pure question "given these comments, who holds the lease and which
 * of them are my own duplicates" — split out when the FR11 content identity joined the race and
 * pushed the lease past the file-size rule.
 *
 * <p>The window, not the whole thread, is the scope on purpose (FR13 of
 * harden-task-branch-contract): a claim comment from a tenure that has since parked, aborted, or
 * been reaped sits before the boundary and is deliberately not part of this race, which is what
 * lets a reclaim by the same instance mint a strictly greater epoch instead of adopting its own
 * dead claim.
 *
 * <p>Implements FR6 of add-tracker-port; FR11, FR13, UX3 of harden-task-branch-contract.
 */
record GithubClaimWindow(List<Entry> comments, int fromIndex) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One marker-bearing comment of the thread, paired with GitHub's server-assigned id. */
    record Entry(long id, ParsedMarker marker) {}

    /**
     * Parses a "List issue comments" body and locates the window: the comments after the newest
     * boundary marker, or the whole thread when there is none.
     *
     * @param commentsJson the raw listing body
     * @param id the task the listing belongs to, for the failure message
     * @return the window over that listing
     */
    static GithubClaimWindow of(String commentsJson, GithubTaskId id) {
        List<Entry> comments = parse(commentsJson, id);
        return new GithubClaimWindow(comments, latestBoundaryIndex(comments) + 1);
    }

    /**
     * The claim that holds the lease: the earliest comment id among the window's claim markers,
     * with {@code ownCommentId} — the comment this attempt just posted — standing as the incumbent
     * so a window holding no other claim resolves to it.
     *
     * <p>GitHub's listing order already reflects the server-side total order, so the scan is by
     * list position and immune to clock skew between racing instances.
     *
     * @param ownCommentId the id of the claim comment this attempt posted
     * @return the winning comment's entry, or empty when {@code ownCommentId} itself wins
     */
    Optional<Entry> winnerAgainst(long ownCommentId) {
        Entry winner = null;
        for (int i = fromIndex; i < comments.size(); i++) {
            Entry candidate = comments.get(i);
            if (candidate.marker().kind() != GithubMarkerKind.CLAIM || candidate.id() == ownCommentId) {
                continue;
            }
            if (isEarlier(candidate.id(), winner == null ? ownCommentId : winner.id())) {
                winner = candidate;
            }
        }
        return Optional.ofNullable(winner);
    }

    /**
     * The claim comments in the window carrying {@code identity} but losing to {@code winnerId} —
     * the duplicates a prior attempt of this same claim left behind after dying before its
     * verify-read (FR11, UX3).
     *
     * @param identity this instance's own claim identity on the task
     * @param winnerId the comment that holds the lease and must be kept
     * @return the ids to delete, in window order
     */
    List<Long> duplicatesOf(GithubCommentIdentity identity, long winnerId) {
        List<Long> duplicates = new ArrayList<>();
        for (int i = fromIndex; i < comments.size(); i++) {
            Entry candidate = comments.get(i);
            if (candidate.id() != winnerId && identity.equals(candidate.marker().identity())) {
                duplicates.add(candidate.id());
            }
        }
        return List.copyOf(duplicates);
    }

    /**
     * The index of the newest boundary marker in the thread — the marker that ended the prior
     * working session and so voids every claim posted before it. The boundary-kind set itself is
     * {@link GithubClaimComment#isBoundary}, reused rather than copied.
     *
     * @return the index, or {@code -1} when the thread carries no boundary
     */
    private static int latestBoundaryIndex(List<Entry> comments) {
        int index = -1;
        for (int i = 0; i < comments.size(); i++) {
            if (GithubClaimComment.isBoundary(comments.get(i).marker().kind())) {
                index = i;
            }
        }
        return index;
    }

    private static List<Entry> parse(String commentsJson, GithubTaskId id) {
        try {
            JsonNode array = MAPPER.readTree(commentsJson);
            List<Entry> comments = new ArrayList<>();
            for (JsonNode comment : array) {
                GithubMarker.parse(comment.get("body").asText())
                        .ifPresent(marker ->
                                comments.add(new Entry(comment.get("id").asLong(), marker)));
            }
            return List.copyOf(comments);
        } catch (JsonProcessingException e) {
            throw new GithubClaimException("Failed to parse comments response for %s/%s#%d"
                    .formatted(id.owner(), id.repo(), id.issueNumber()));
        }
    }

    // PIT M4 documented exception (build.gradle has the full rationale style): @DoNotMutate
    // because `<` vs `<=` (ConditionalsBoundaryMutator) is a genuine equivalent mutant here —
    // GitHub comment ids are server-assigned and always distinct within one issue, so `candidateId
    // <= winnerId` can only ever disagree with `candidateId < winnerId` at candidateId ==
    // winnerId, a value this comparison never receives (the incumbent is skipped by id above).
    // Covered at the ordinary test level by GithubClaimLeaseSpec's claim-race scenario.
    @DoNotMutate
    private static boolean isEarlier(long candidateId, long winnerId) {
        return candidateId < winnerId;
    }
}
