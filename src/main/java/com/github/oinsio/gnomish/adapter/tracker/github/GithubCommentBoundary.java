package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The claim/abort boundary over a task's parsed structural markers, in
 * comment order: the latest {@link GithubMarkerKind#CLAIM} or {@link
 * GithubMarkerKind#ABORT} marker anchors who currently holds the task and how
 * many aborts belong to the active retry streak. {@link GithubTaskFetcher}
 * uses it so a stale abort or a superseded claim recorded before the
 * currently active claim never leaks into {@code fetchTask}'s reported facts
 * (github-tracker spec risk: "a human deleting factory comments resets
 * history" — the mirror risk here is a *stale* marker persisting past its
 * boundary). A {@code report}-kind park/finish marker sitting before the
 * active claim is not itself a boundary kind here, but it still stops the
 * backward abort-streak scan in {@link #abortFactsSinceBoundary(List)}, so
 * recorded durable progress correctly ends the streak.
 *
 * <p>This is a distinct boundary from the session-ending boundary {@link
 * GithubClaimLease} uses to void stale claims during a lease race — that one
 * is the latest {@code abort} or {@code report} ({@code park} and {@code
 * finish} are the two REPORT-kind markers {@link GithubStateWrites} writes;
 * {@code release} posts no GitHub marker, design D2). Both descend from
 * design D13's "since the newest boundary marker" idea but answer different
 * questions — "who holds it now, and how many aborts this streak" here versus
 * "which claims are still fresh" there — so each keeps its own marker-kind
 * set rather than collapsing into one.
 *
 * <p>Implements FR2, FR5 of add-tracker-port.
 */
final class GithubCommentBoundary {

    private GithubCommentBoundary() {}

    /**
     * Returns the index (within {@code markers}, in comment order) of the
     * latest CLAIM or ABORT marker, or empty if none is present.
     */
    static Optional<Integer> latestBoundaryIndex(List<ParsedMarker> markers) {
        Integer boundaryIndex = null;
        Instant boundaryAt = null;
        for (int i = 0; i < markers.size(); i++) {
            ParsedMarker marker = markers.get(i);
            if (marker.kind() != GithubMarkerKind.CLAIM && marker.kind() != GithubMarkerKind.ABORT) {
                continue;
            }
            if (boundaryAt == null || !marker.at().isBefore(boundaryAt)) {
                boundaryIndex = i;
                boundaryAt = marker.at();
            }
        }
        return Optional.ofNullable(boundaryIndex);
    }

    /**
     * Returns the latest CLAIM marker among {@code markers} that is at or
     * after the latest boundary marker (i.e. the still-active claim: a claim
     * not yet superseded by a later ABORT). Empty when no CLAIM marker is
     * active — e.g. the latest boundary marker is itself an ABORT.
     */
    static Optional<ParsedMarker> activeClaim(List<ParsedMarker> markers) {
        Optional<Integer> boundaryIndex = latestBoundaryIndex(markers);
        if (boundaryIndex.isEmpty()) {
            return Optional.empty();
        }
        ParsedMarker atBoundary = markers.get(boundaryIndex.get());
        return atBoundary.kind() == GithubMarkerKind.CLAIM ? Optional.of(atBoundary) : Optional.empty();
    }

    /**
     * Folds ABORT markers into {@link AbortFacts}: "aborts since last durable
     * progress" (tracker-port spec, {@code AbortFacts} Javadoc) — NOT simply
     * reset by every reclaim: a holder retrying after its own abort keeps its
     * streak, while a {@code report}-kind park/finish (durable progress) or a
     * different instance's claim ends it. Two cases:
     *
     * <ul>
     *   <li>the latest boundary marker is ABORT — the task is back in {@code
     *       Ready}; every ABORT marker at or after that boundary folds in
     *       (a stale ABORT before it, already superseded by an even earlier
     *       reclaim, is excluded);
     *   <li>the latest boundary marker is CLAIM — the task is {@code Working};
     *       ABORT markers immediately preceding that claim fold in ONLY while
     *       they belong to the SAME retry streak: scanning backward from the
     *       active claim, an ABORT posted by the active claim's own holder
     *       continues the streak (that holder is retrying after its own
     *       abort), but a CLAIM by a DIFFERENT instance ends it — that
     *       instance's claim starts fresh, and whatever aborts came before
     *       are stale history a fresh claimant never asked to inherit
     *       (matching "boundary-anchors the claim holder" in {@code
     *       GithubTaskFetcherSpec}: a different holder's reclaim resets).
     * </ul>
     */
    static AbortFacts abortFactsSinceBoundary(List<ParsedMarker> markers) {
        Optional<Integer> boundaryIndex = latestBoundaryIndex(markers);
        if (boundaryIndex.isEmpty()) {
            return AbortFacts.none();
        }
        ParsedMarker atBoundary = markers.get(boundaryIndex.get());
        if (atBoundary.kind() == GithubMarkerKind.ABORT) {
            return foldAbortsFrom(markers, boundaryIndex.get());
        }
        return foldAbortStreakBeforeClaim(markers, boundaryIndex.get(), atBoundary.instance());
    }

    private static AbortFacts foldAbortsFrom(List<ParsedMarker> markers, int fromIndexInclusive) {
        int count = 0;
        Instant lastAbortAt = null;
        for (int i = fromIndexInclusive; i < markers.size(); i++) {
            ParsedMarker marker = markers.get(i);
            if (marker.kind() != GithubMarkerKind.ABORT) {
                continue;
            }
            count++;
            if (lastAbortAt == null || marker.at().isAfter(lastAbortAt)) {
                lastAbortAt = marker.at();
            }
        }
        return count == 0 ? AbortFacts.none() : new AbortFacts(count, lastAbortAt);
    }

    /**
     * Scans backward from {@code claimIndex} (exclusive), folding ABORT
     * markers posted by {@code claimHolder} into the retry streak, stopping
     * at the first marker that is not one of that holder's own ABORT
     * markers (a different instance's CLAIM, or the start of history).
     */
    private static AbortFacts foldAbortStreakBeforeClaim(
            List<ParsedMarker> markers, int claimIndex, String claimHolder) {
        int count = 0;
        Instant lastAbortAt = null;
        for (int i = claimIndex - 1; i >= 0; i--) {
            ParsedMarker marker = markers.get(i);
            if (marker.kind() != GithubMarkerKind.ABORT || !marker.instance().equals(claimHolder)) {
                break;
            }
            count++;
            if (lastAbortAt == null || marker.at().isAfter(lastAbortAt)) {
                lastAbortAt = marker.at();
            }
        }
        return count == 0 ? AbortFacts.none() : new AbortFacts(count, lastAbortAt);
    }
}
