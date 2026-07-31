package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the park reason of a {@code needs-human} issue from its parsed
 * structural markers (design D9's {@code report}-kind park marker): the {@code
 * reason} field of the latest (highest comment-order) {@link
 * GithubMarkerKind#REPORT} marker that carries one. The symmetric sibling of
 * {@link GithubClaimComment} — that one resolves the claim marker for a {@code
 * Working} task, this one the park reason for an {@code AwaitingHuman} task —
 * shared so {@link GithubTaskFetcher} ({@code fetchTask}) and {@link
 * GithubOpenQuery} ({@code listOpen}) derive it from one place rather than each
 * re-implementing the scan.
 *
 * <p>Implements FR2, FR5 of add-tracker-port; FR5 of add-claim-heartbeat.
 */
final class GithubParkReason {

    private GithubParkReason() {}

    /**
     * Returns the park reason of the latest report marker carrying one.
     *
     * @throws GithubFeedQueryException if no report marker with a reason exists —
     *     an issue wearing the needs-human label but missing its park marker is
     *     an inconsistency, surfaced as an infrastructure failure
     */
    static ParkReason latest(List<ParsedMarker> markers) {
        for (int i = markers.size() - 1; i >= 0; i--) {
            ParsedMarker marker = markers.get(i);
            if (marker.kind() == GithubMarkerKind.REPORT && marker.reason() != null) {
                return ParkReason.valueOf(marker.reason().toUpperCase(Locale.ROOT));
            }
        }
        throw new GithubFeedQueryException(
                "issue carries the needs-human label but no report marker with a reason was found");
    }
}
