package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The claim/abort boundary over a task's parsed structural markers, in comment
 * order: the latest {@link GithubMarkerKind#CLAIM} or {@link
 * GithubMarkerKind#ABORT} marker anchors who currently holds the task, and the
 * latest {@link GithubMarkerKind#PROGRESS} marker anchors how many aborts belong
 * to the active retry streak. {@link GithubTaskFetcher} uses it so a stale abort
 * or a superseded claim recorded before the active claim never leaks into {@code
 * fetchTask}'s facts (the mirror of the github-tracker spec risk "a human
 * deleting factory comments resets history": a *stale* marker persisting past
 * its boundary).
 *
 * <p>Abort-count reconstruction ({@link #abortFactsSinceBoundary(List)}) is
 * PRIMARILY anchored to the latest {@code PROGRESS} marker — only ABORT markers
 * strictly after it count, per FR3/design D3 of fix-abort-progress-reset
 * ("aborts since the last durably persisted round"). With no {@code PROGRESS}
 * marker (a task predating it, or one with no durable round persisted), the
 * claim-streak logic below is the FALLBACK, unchanged from before that change.
 *
 * <p>{@code PROGRESS} is deliberately excluded from {@link
 * #latestBoundaryIndex(List)} (and thus {@link #activeClaim(List)}): claim
 * resolution stays over CLAIM/ABORT only, since a progress marker sits inside an
 * active claim and must not read as "no active claim" (design D3).
 *
 * <p>Distinct from the session-ending boundary {@link GithubClaimLease} uses to
 * void stale claims in a lease race (the latest {@code abort}/{@code park}/{@code
 * finish}; {@code release} posts no marker, design D2). Both descend from design
 * D13's "since the newest boundary marker" but answer different questions — "who
 * holds it now, and how many aborts this streak" versus "which claims are still
 * fresh" — so each keeps its own marker-kind set.
 *
 * <p>Implements FR2, FR5 of add-tracker-port; FR3 of fix-abort-progress-reset.
 */
final class GithubCommentBoundary {

    private GithubCommentBoundary() {}

    /** Returns the index (in comment order) of the latest CLAIM or ABORT marker, or empty if none. */
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
     * Returns the latest CLAIM marker at or after the latest boundary marker
     * (the still-active claim, not yet superseded by a later ABORT). Empty when
     * the latest boundary marker is itself an ABORT.
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
     * Folds ABORT markers into {@link AbortFacts}: "aborts since the last
     * durably persisted round" (FR3 of fix-abort-progress-reset, design D3).
     * PRIMARY rule: with a {@link GithubMarkerKind#PROGRESS} marker present,
     * only ABORT markers strictly AFTER the latest one count — a durable round
     * resets the streak, so an ABORT at or before it is stale history.
     *
     * <p>FALLBACK, with no {@code PROGRESS} marker (a task predating it, or one
     * with no durable round persisted): the claim-streak logic — a holder
     * retrying after its own abort keeps its streak, a different instance's
     * claim ends it. Two cases:
     *
     * <ul>
     *   <li>latest boundary marker is ABORT — the task is back in {@code Ready};
     *       every ABORT at or after that boundary folds in (a stale ABORT before
     *       it, already superseded by an earlier reclaim, is excluded);
     *   <li>latest boundary marker is CLAIM — the task is {@code Working}; ABORT
     *       markers preceding that claim fold in ONLY within the SAME retry
     *       streak: scanning backward, an ABORT by the active holder continues
     *       the streak (retrying after its own abort), but a CLAIM by a
     *       DIFFERENT instance ends it — that claim starts fresh and earlier
     *       aborts are stale history it never asked to inherit (matching
     *       "boundary-anchors the claim holder" in {@code GithubTaskFetcherSpec}).
     * </ul>
     */
    static AbortFacts abortFactsSinceBoundary(List<ParsedMarker> markers) {
        Optional<Integer> progressIndex = latestProgressIndex(markers);
        if (progressIndex.isPresent()) {
            return foldAbortsAfter(markers, progressIndex.get());
        }
        Optional<Integer> boundaryIndex = latestBoundaryIndex(markers);
        if (boundaryIndex.isEmpty()) {
            return AbortFacts.none();
        }
        ParsedMarker atBoundary = markers.get(boundaryIndex.get());
        if (atBoundary.kind() == GithubMarkerKind.ABORT) {
            return foldAbortsAfter(markers, boundaryIndex.get() - 1);
        }
        return foldAbortStreakBeforeClaim(markers, boundaryIndex.get(), atBoundary.instance());
    }

    /**
     * Returns the index (in comment order) of the latest {@link
     * GithubMarkerKind#PROGRESS} marker, or empty if none. Mirrors {@link
     * #latestBoundaryIndex(List)}'s "pick the latest timestamp" rule but over
     * PROGRESS only. Anchoring to the LATEST marker (rather than counting
     * repeats) is what makes a second {@code recordProgress} within the same
     * claim harmless (NFR-R2 of fix-abort-progress-reset): reconstruction always
     * resolves to one boundary regardless of how many PROGRESS markers precede.
     *
     * <p>Package-private: {@link GithubAbortFactsReader} reuses this and {@link
     * #foldAbortsAfter(List, int)} so the PROGRESS-anchor rule has one
     * implementation shared by {@code listReady} and {@code fetchTask} (FR3,
     * design D3 of fix-abort-progress-reset).
     */
    static Optional<Integer> latestProgressIndex(List<ParsedMarker> markers) {
        Integer progressIndex = null;
        Instant progressAt = null;
        for (int i = 0; i < markers.size(); i++) {
            ParsedMarker marker = markers.get(i);
            if (marker.kind() != GithubMarkerKind.PROGRESS) {
                continue;
            }
            if (progressAt == null || !marker.at().isBefore(progressAt)) {
                progressIndex = i;
                progressAt = marker.at();
            }
        }
        return Optional.ofNullable(progressIndex);
    }

    /**
     * Folds ABORT markers strictly AFTER {@code exclusiveFromIndex} (the latest
     * PROGRESS marker's index) into {@link AbortFacts} — that marker and
     * anything at or before it is excluded: a durable round resets the streak.
     */
    static AbortFacts foldAbortsAfter(List<ParsedMarker> markers, int exclusiveFromIndex) {
        int count = 0;
        Instant lastAbortAt = null;
        for (int i = exclusiveFromIndex + 1; i < markers.size(); i++) {
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
     * Scans backward from {@code claimIndex} (exclusive), folding ABORT markers
     * posted by {@code claimHolder} into the retry streak. A CLAIM marker by
     * that same holder is a self-reclaim within the streak — skipped, not a stop
     * condition — so a holder that aborted and reclaimed more than once keeps
     * every abort. The scan stops at the first marker belonging to neither the
     * holder's own ABORTs nor its own CLAIMs (a different instance's marker), or
     * at the start of history.
     */
    private static AbortFacts foldAbortStreakBeforeClaim(
            List<ParsedMarker> markers, int claimIndex, String claimHolder) {
        int count = 0;
        Instant lastAbortAt = null;
        for (int i = claimIndex - 1; i >= 0; i--) {
            ParsedMarker marker = markers.get(i);
            if (marker.kind() == GithubMarkerKind.CLAIM) {
                if (!marker.instance().equals(claimHolder)) {
                    break;
                }
                continue;
            }
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
