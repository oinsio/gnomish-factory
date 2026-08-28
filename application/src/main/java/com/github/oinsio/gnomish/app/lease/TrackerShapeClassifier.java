package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind;
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts;
import com.github.oinsio.gnomish.app.port.tracker.StateLabels;
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts;

/**
 * The one place adapter-reported {@link TrackerFacts} become a named {@link TrackerShape} (design
 * D16, the mirror of the branch classifier): total over every combination of labels, claim
 * footprint, and boundary marker, and pure — no clock, no tracker call, no time judgment. Staleness
 * TTL and window grace stay with {@link StalenessMemory}; adapters report facts and never judge.
 *
 * <p>The rows are made disjoint by the precedence the {@code claim-heartbeat} capability fixes:
 *
 * <ol>
 *   <li>a closed task classifies {@code Revoked} over every other fact;
 *   <li>a boundary marker recorded after the newest claim while the working label is still on
 *       classifies {@code IndexLagging} — the truth landed, its index did not;
 *   <li>among the working-labeled shapes the claim footprint separates {@code Claimed}, {@code
 *       ClaimPending}, and {@code ClaimAbandoned};
 *   <li>a ready-labeled task still carrying a <em>live</em> claim marker is the suspension leftover
 *       — a footprint no working tenure backs, so {@code ClaimAbandoned} again, retired by the same
 *       stale-claim removal; a dead footprint on a ready task is merely the history of the tenure
 *       the boundary already ended and changes nothing;
 *   <li>recorded park/finish history separates {@code Returned} from {@code Ready};
 *   <li>only a combination matching no row above classifies {@code Foreign}.
 * </ol>
 *
 * <p>Implements FR19, FR12 of harden-task-branch-contract.
 */
public final class TrackerShapeClassifier {

    private TrackerShapeClassifier() {}

    /**
     * Classifies one task's observed facts.
     *
     * <p>Implements FR19, FR12 of harden-task-branch-contract.
     *
     * @param facts the facts an adapter reported; never null
     * @return the one shape those facts are; never null
     */
    public static TrackerShape classify(TrackerFacts facts) {
        StateLabels labels = facts.labels();
        if (labels.closed()) {
            return new TrackerShape.Revoked();
        }
        if (labels.working()) {
            return workingShape(facts);
        }
        if (labels.needsHuman() && !labels.delivered()) {
            return new TrackerShape.Parked();
        }
        if (labels.delivered() && !labels.ready()) {
            return new TrackerShape.Finished();
        }
        if (labels.ready()) {
            return readyShape(facts);
        }
        return new TrackerShape.Foreign("no gnomish state label present, claim " + facts.claim());
    }

    /** The working-labeled rows: a lagging index first, then the claim footprint's three cases. */
    private static TrackerShape workingShape(TrackerFacts facts) {
        BoundaryKind boundary = facts.latestBoundary();
        if (boundary != null) {
            return new TrackerShape.IndexLagging(boundary);
        }
        return switch (facts.claim()) {
            case ClaimFacts.Live live -> new TrackerShape.Claimed(live);
            case ClaimFacts.Dead dead -> new TrackerShape.ClaimAbandoned(dead);
            case ClaimFacts.None ignored -> new TrackerShape.ClaimPending();
        };
    }

    /** The ready-labeled rows: the ghost claim first, then park/finish history, then the queue. */
    private static TrackerShape readyShape(TrackerFacts facts) {
        if (facts.claim() instanceof ClaimFacts.Live live) {
            return new TrackerShape.ClaimAbandoned(live);
        }
        BoundaryKind boundary = facts.latestBoundary();
        if (boundary == BoundaryKind.PARK || boundary == BoundaryKind.FINISH) {
            return new TrackerShape.Returned();
        }
        return new TrackerShape.Ready();
    }
}
