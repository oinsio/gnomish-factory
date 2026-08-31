package com.github.oinsio.gnomish.app.port.tracker.contract

import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.StateLabels
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import java.time.Instant

/**
 * The fact combinations a killed tracker sequence can freeze — the meeting point of the two halves
 * of the tracker kill-point harness (task 9.1b, FR19, M1 of harden-task-branch-contract).
 *
 * <p>The halves live in different modules because the classifier does: {@code
 * GithubKillWindowSpec} freezes real windows against the one adapter whose writes are physically
 * non-atomic and asserts each frozen state is {@link #enumerates enumerated} here, while {@code
 * TrackerKillWindowShapeSpec} classifies every combination listed here and asserts none of them is
 * {@code Foreign} or ownerless. A window neither of them can name fails one side or the other.
 *
 * <p>A combination is identified by its {@link #signature}: what classification actually turns on —
 * the labels present, whether a claim footprint is live, dead or absent, and the newest boundary
 * marker after it. Holder names and claim versions are deliberately not part of it; no shape turns
 * on them.
 */
final class TrackerKillWindows {

    private static final ClaimVersion VERSION =
    new ClaimVersion('marker-1', Instant.parse('2026-07-20T10:00:00Z'), new ClaimEpoch(1L))

    /**
     * Every fact combination the five killed sequences actually freeze, recorded by {@code
     * GithubKillWindowSpec} and classified by {@code TrackerKillWindowShapeSpec}.
     *
     * <p>{@code GithubKillWindowSpec} checks this list in <em>both</em> directions, which is what
     * makes it a contract rather than a wish: each window it freezes must appear here (a new
     * window fails), and every entry here must be one some sequence still freezes (an entry that
     * stopped happening fails). One direction alone would let the list rot — a stale signature
     * would go on being dutifully classified by the other half while describing nothing real.
     */
    static final List<String> SIGNATURES = [
        // The claim sequence: nothing written yet, then the label pair mid-transition.
        'ready|claim=None|boundary=none',
        'ready+working|claim=None|boundary=none',
        // The held tenure the park, finish and abort sequences start from.
        'working|claim=Live|boundary=none',
        // A boundary marker posted while the label flip it implies is still outstanding.
        'working|claim=Dead|boundary=PARK',
        'working|claim=Dead|boundary=FINISH',
        'working|claim=Dead|boundary=ABORT',
        'working|claim=Dead|boundary=STALE_CLAIM_REMOVED',
        // The label pair mid-flip, after the marker landed.
        'working+needsHuman|claim=Dead|boundary=PARK',
        'working+delivered|claim=Dead|boundary=FINISH',
        'ready+working|claim=Dead|boundary=ABORT',
        // The reap sequence, whose marker removal retires the footprint before the label flip.
        'working|claim=None|boundary=none',
        'working|claim=None|boundary=STALE_CLAIM_REMOVED',
        'ready+working|claim=None|boundary=STALE_CLAIM_REMOVED',
    ]

    private TrackerKillWindows() {}

    /** What classification turns on, as one comparable string. */
    static String signature(TrackerFacts facts) {
        "${labelsOf(facts.labels())}|claim=${claimOf(facts.claim())}|boundary=${facts.latestBoundary() ?: 'none'}"
    }

    /** Whether {@code facts} is one of the enumerated windows. */
    static boolean enumerates(TrackerFacts facts) {
        SIGNATURES.contains(signature(facts))
    }

    /** Rebuilds a canonical {@link TrackerFacts} for {@code signature}, for the classifying half. */
    static TrackerFacts facts(String signature) {
        def (String labels, String claim, String boundary) = signature.split(/\|/) as List
        new TrackerFacts(labelsFor(labels), claimFor(claim - 'claim='), boundaryFor(boundary - 'boundary='))
    }

    private static String labelsOf(StateLabels labels) {
        List<String> present = []
        if (labels.ready()) {
            present << 'ready'
        }
        if (labels.working()) {
            present << 'working'
        }
        if (labels.needsHuman()) {
            present << 'needsHuman'
        }
        if (labels.delivered()) {
            present << 'delivered'
        }
        if (labels.closed()) {
            present << 'closed'
        }
        present ? present.join('+') : 'none'
    }

    private static StateLabels labelsFor(String labels) {
        def present = labels.split(/\+/) as List
        new StateLabels(present.contains('ready'), present.contains('working'),
                present.contains('needsHuman'), present.contains('delivered'), present.contains('closed'))
    }

    private static String claimOf(ClaimFacts claim) {
        claim.getClass().simpleName
    }

    private static ClaimFacts claimFor(String claim) {
        switch (claim) {
            case 'Live': return new ClaimFacts.Live('holder', VERSION)
            case 'Dead': return new ClaimFacts.Dead('holder')
            default: return new ClaimFacts.None()
        }
    }

    private static BoundaryKind boundaryFor(String boundary) {
        boundary == 'none' ? null : BoundaryKind.valueOf(boundary)
    }
}
