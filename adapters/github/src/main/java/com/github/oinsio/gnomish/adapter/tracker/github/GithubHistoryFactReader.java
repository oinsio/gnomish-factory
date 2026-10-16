package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Derives a {@code Ready} task's {@code returned} and {@code finished} facts
 * (FR1, FR2 of enforce-finish-terminality) from its already-parsed structural
 * markers, purely structurally rather than from adapter-local state (design
 * D1).
 *
 * <p>{@code returned} — "this task was previously worked and given back" —
 * is true iff the thread carries a {@link GithubMarkerKind#PARK} marker
 * (human-returned: claimed, then given back with a report) or a {@link
 * GithubMarkerKind#STALE_CLAIM_REMOVED} marker (the reaper's holder-transition
 * boundary marker, design D12 of add-claim-heartbeat). A {@link
 * GithubMarkerKind#FINISH} marker never counts as {@code returned}: {@code
 * park} and {@code finish} are structurally distinct kinds (design D1), so a
 * finished-then-reopened task is never mistaken for a returned one.
 *
 * <p>{@code finished} is true iff the thread carries a {@link
 * GithubMarkerKind#FINISH} marker; false otherwise.
 *
 * <p>Both derivations are a plain "did this ever happen" over the full
 * thread, not anchored to the latest CLAIM/ABORT/PROGRESS boundary the way
 * {@link GithubCommentBoundary}'s claim/abort logic is — unlike that logic,
 * these facts stay true forever once their marker has appeared, and the two
 * facts are independent: a task parked once and later finished reports both
 * {@code returned = true} and {@code finished = true}.
 *
 * <p>The tenure boundary is the third whole-thread fact: the kind of the last PARK / FINISH /
 * ABORT / STALE_CLAIM_REMOVED marker in posting order, whatever claim markers follow it (design D1
 * of fix-terminal-receipt-convergence). It is deliberately not {@link
 * GithubTrackerFacts#latestBoundary}, which answers only for a boundary posted after the newest
 * claim and so is null once the next tenure has begun.
 *
 * <p>Takes an already-fetched {@link ParsedMarker} list rather than fetching
 * its own comments: {@link GithubFeedQuery} fetches the comments thread once
 * per issue (via {@link GithubAbortFactsReader#fetchMarkers}) and reuses it
 * for both {@link com.github.oinsio.gnomish.app.port.tracker.AbortFacts} and
 * these facts, so deriving them costs no additional GitHub API call (NFR-P1
 * of add-factory-serve).
 *
 * <p>Implements FR7, NFR-P1 of add-factory-serve; FR1, FR2 of
 * enforce-finish-terminality; FR1, NFR-C1 of fix-terminal-receipt-convergence.
 */
final class GithubHistoryFactReader {

    private GithubHistoryFactReader() {}

    /**
     * Returns true iff {@code markers} contains a PARK or
     * STALE_CLAIM_REMOVED structural marker anywhere in the thread.
     */
    static boolean derive(List<ParsedMarker> markers) {
        return hasKind(markers, GithubMarkerKind.PARK) || hasKind(markers, GithubMarkerKind.STALE_CLAIM_REMOVED);
    }

    /** Returns true iff {@code markers} contains a FINISH structural marker anywhere in the thread. */
    static boolean deriveFinished(List<ParsedMarker> markers) {
        return hasKind(markers, GithubMarkerKind.FINISH);
    }

    /**
     * Returns the kind of the last boundary marker in {@code markers} (posting order), whatever
     * claim markers follow it, or {@code null} when the thread carries none (FR1 of
     * fix-terminal-receipt-convergence).
     */
    static @Nullable BoundaryKind deriveTenureBoundary(List<ParsedMarker> markers) {
        for (int i = markers.size() - 1; i >= 0; i--) {
            BoundaryKind boundary = GithubTrackerFacts.boundaryOf(markers.get(i).kind());
            if (boundary != null) {
                return boundary;
            }
        }
        return null;
    }

    private static boolean hasKind(List<ParsedMarker> markers, GithubMarkerKind kind) {
        for (ParsedMarker marker : markers) {
            if (marker.kind() == kind) {
                return true;
            }
        }
        return false;
    }
}
