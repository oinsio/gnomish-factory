package com.github.oinsio.gnomish.adapter.tracker.github;

import java.util.List;

/**
 * Derives a {@code Ready} task's {@code returned} fact — "this task was
 * previously worked and given back" (FR6, FR7 of add-factory-serve) — from
 * its already-parsed structural markers: true iff the thread carries a
 * {@link GithubMarkerKind#REPORT} marker ({@link GithubStateWrites} writes
 * this kind both for {@code park} and {@code finish}; only {@code park}'s can
 * appear on a task that later moves back to {@code Ready}, since {@code
 * finish} is terminal) or a {@link GithubMarkerKind#STALE_CLAIM_REMOVED}
 * marker (the reaper's
 * holder-transition boundary marker, design D12 of add-claim-heartbeat).
 * Either marker records a "worked, then returned to Ready" history that
 * survives the transition back to {@code Ready} even though that transition
 * itself (a human editing the tracker UI, or the reaper) writes nothing new.
 *
 * <p>Unlike {@link GithubCommentBoundary}'s claim/abort boundary logic, this
 * derivation does not anchor to the latest CLAIM/ABORT/PROGRESS boundary —
 * the returned fact is a plain "did this ever happen" over the full thread,
 * not a "since the active streak" count. A REPORT or STALE_CLAIM_REMOVED
 * marker from an earlier round is still true evidence the task was returned.
 *
 * <p>Takes an already-fetched {@link ParsedMarker} list rather than fetching
 * its own comments: {@link GithubFeedQuery} fetches the comments thread once
 * per issue (via {@link GithubAbortFactsReader#fetchMarkers}) and reuses it
 * for both {@link com.github.oinsio.gnomish.app.port.tracker.AbortFacts} and
 * this fact, so deriving {@code returned} costs no additional GitHub API call
 * (NFR-P1 of add-factory-serve).
 *
 * <p>Implements FR7, NFR-P1 of add-factory-serve.
 */
final class GithubReturnedFactReader {

    private GithubReturnedFactReader() {}

    /**
     * Returns true iff {@code markers} contains a REPORT or
     * STALE_CLAIM_REMOVED structural marker anywhere in the thread.
     */
    static boolean derive(List<ParsedMarker> markers) {
        for (ParsedMarker marker : markers) {
            if (marker.kind() == GithubMarkerKind.REPORT || marker.kind() == GithubMarkerKind.STALE_CLAIM_REMOVED) {
                return true;
            }
        }
        return false;
    }
}
