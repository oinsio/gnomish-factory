package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Resolves the park reason of a {@code needs-human} issue from its parsed
 * structural markers (design D9's dedicated {@code park}-kind marker,
 * enforce-finish-terminality task 3.1): the {@code reason} field of the
 * latest (highest comment-order) {@link GithubMarkerKind#PARK} marker. The
 * symmetric sibling of {@link GithubClaimComment} — that one resolves the
 * claim marker for a {@code Working} task, this one the park reason for an
 * {@code AwaitingHuman} task — shared so {@link GithubTaskFetcher} ({@code
 * fetchTask}) and {@link GithubOpenQuery} ({@code listOpen}) derive it from
 * one place rather than each re-implementing the scan.
 *
 * <p>Matching is on kind alone (no reason-presence inference): a {@code PARK}
 * marker is only ever written by {@link GithubStateWrites#park}, which always
 * supplies a reason, so distinguishing park from finish never depends on
 * whether {@code reason()} happens to be non-null (enforce-finish-terminality
 * design D1).
 *
 * <p>Implements FR2, FR5 of add-tracker-port; FR5 of add-claim-heartbeat;
 * enforce-finish-terminality design D1 (park resolved by the dedicated {@code
 * PARK} kind alone, no reason-presence inference).
 */
final class GithubParkReason {

    private GithubParkReason() {}

    /**
     * Returns the park reason of the latest {@code PARK} marker.
     *
     * @throws GithubFeedQueryException if no {@code PARK} marker exists — an issue wearing the
     *     needs-human label but missing its park marker is an inconsistency, surfaced as an
     *     infrastructure failure
     */
    static ParkReason latest(List<ParsedMarker> markers) {
        for (int i = markers.size() - 1; i >= 0; i--) {
            ParsedMarker marker = markers.get(i);
            if (marker.kind() == GithubMarkerKind.PARK) {
                return ParkReason.valueOf(
                        Objects.requireNonNull(marker.reason(), "PARK marker missing its reason field")
                                .toUpperCase(Locale.ROOT));
            }
        }
        throw new GithubFeedQueryException("issue carries the needs-human label but no PARK marker was found");
    }
}
