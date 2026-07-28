package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The claim/abort boundary over a task's parsed structural markers, in
 * comment order: the latest {@link GithubMarkerKind#CLAIM} or {@link
 * GithubMarkerKind#ABORT} marker anchors who currently holds the task, and
 * the latest {@link GithubMarkerKind#PROGRESS} marker anchors how many
 * aborts belong to the active retry streak. {@link GithubTaskFetcher} uses it
 * so a stale abort or a superseded claim recorded before the currently active
 * claim never leaks into {@code fetchTask}'s reported facts (github-tracker
 * spec risk: "a human deleting factory comments resets history" — the mirror
 * risk here is a *stale* marker persisting past its boundary).
 *
 * <p>Abort-count reconstruction ({@link #abortFactsSinceBoundary(List)}) is
 * PRIMARILY anchored to the latest {@code PROGRESS} marker: only ABORT
 * markers strictly after it count, per FR3/design D3 of
 * fix-abort-progress-reset — "aborts since the last durably persisted round".
 * When no {@code PROGRESS} marker is present (a task predating the marker, or
 * one that never had a durable round persisted), the claim-streak logic below
 * is the FALLBACK, unchanged from before that change.
 *
 * <p>{@code PROGRESS} is deliberately excluded from {@link
 * #latestBoundaryIndex(List)} and therefore from {@link #activeClaim(List)}:
 * claim-holder resolution stays over CLAIM/ABORT only, since a progress
 * marker sits inside an active claim and must not read as "no active claim"
 * (design D3 of fix-abort-progress-reset).
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
 * <p>Implements FR2, FR5 of add-tracker-port; FR3 of
 * fix-abort-progress-reset.
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
     * Folds ABORT markers into {@link AbortFacts}: "aborts since the last
     * durably persisted round" (FR3 of fix-abort-progress-reset, design D3;
     * tracker-port spec, {@code AbortFacts} Javadoc). PRIMARY rule: when a
     * {@link GithubMarkerKind#PROGRESS} marker is present, only ABORT markers
     * strictly AFTER the latest one count — a durable round resets the
     * streak, so an ABORT at or before that marker is stale history the reset
     * already absorbed.
     *
     * <p>FALLBACK, when no {@code PROGRESS} marker is present (a task that
     * predates the marker, or never had a durable round persisted): the
     * claim-streak logic — NOT simply reset by every reclaim, a holder
     * retrying after its own abort keeps its streak, while a different
     * instance's claim ends it. Two cases:
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
     * Returns the index (within {@code markers}, in comment order) of the
     * latest {@link GithubMarkerKind#PROGRESS} marker, or empty if none is
     * present. Mirrors {@link #latestBoundaryIndex(List)}'s "pick the latest
     * timestamp" rule but over the PROGRESS kind only — PROGRESS is
     * deliberately not folded into that method (design D3 of
     * fix-abort-progress-reset). Anchoring to the LATEST marker rather than
     * counting or special-casing repeats is exactly what makes a second
     * {@code recordProgress} within the same claim harmless (NFR-R2 of
     * fix-abort-progress-reset): reconstruction always resolves to one
     * boundary regardless of how many PROGRESS markers precede it.
     *
     * <p>Package-private: {@link GithubAbortFactsReader} reuses this and
     * {@link #foldAbortsAfter(List, int)} directly so the PROGRESS-anchor
     * rule has one implementation shared by both {@code listReady} and
     * {@code fetchTask} (FR3, design D3 of fix-abort-progress-reset).
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
     * Folds ABORT markers strictly AFTER {@code exclusiveFromIndex} (the
     * latest PROGRESS marker's index) into {@link AbortFacts} — the marker
     * itself, and anything at or before it, is excluded: a durable round
     * resets the streak.
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
     * Scans backward from {@code claimIndex} (exclusive), folding ABORT
     * markers posted by {@code claimHolder} into the retry streak. A CLAIM
     * marker by that same holder is a self-reclaim within the same streak —
     * it is skipped over, not a stop condition — so a holder that has
     * aborted and reclaimed more than once keeps every abort in the streak.
     * The scan stops at the first marker that belongs to neither the
     * holder's own ABORT markers nor its own CLAIM markers (a different
     * instance's CLAIM or ABORT), or at the start of history.
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
