package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind;
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts;

/**
 * The classification of a task's tracker-side state: the labels present, the claim footprint, and
 * the newest boundary marker after that footprint mapped to exactly one name from a closed set of
 * ten. Sealed, so every reader switches without a default branch and adding a shape fails the build
 * until each reader names it — the D16 mirror of the branch medium's own classifier.
 *
 * <p>The shapes and their meanings are owned by the {@code claim-heartbeat} capability, in its
 * "Total tracker-shape classification" requirement — this type realizes that table and does not
 * restate it. The recovery owner per shape is realized by {@link #recoveryOwner()}, keeping the
 * whole mapping readable in one place rather than scattered over ten bodies.
 *
 * <p>Implements FR19, FR12 of harden-task-branch-contract.
 */
public sealed interface TrackerShape {

    /** Ready label, no live claim: the task is in the queue. */
    record Ready() implements TrackerShape {}

    /**
     * Working label with a live claim: the ordinary held tenure.
     *
     * @param claim the live claim footprint, holder and version; never null
     */
    record Claimed(ClaimFacts.Live claim) implements TrackerShape {}

    /** Needs-human label with a park marker latest: a human owns it. */
    record Parked() implements TrackerShape {}

    /** Delivered label with a finish marker present: terminal. */
    record Finished() implements TrackerShape {}

    /** Ready label with park or finish history: a human gave the task back. */
    record Returned() implements TrackerShape {}

    /** The task itself is closed in the tracker: terminal, and no factory write follows. */
    record Revoked() implements TrackerShape {}

    /**
     * Working label with no claim footprint at all — the claim sequence's own kill window, frozen
     * between the working-label flip and the claim marker it never posted.
     */
    record ClaimPending() implements TrackerShape {}

    /**
     * A claim footprint no live tenure backs: a working-labeled task whose claim marker is gone
     * (leaving the last-known holder), or a ready-labeled task still carrying a live claim marker —
     * the suspension leftover of a claim that was rolled back while its comment was in flight.
     * Either way a footprint survives that no working tenure owns, and the port's stale-claim
     * removal is what retires it.
     *
     * @param claim the footprint observed — dead on a working task, live on a ready one; never null
     */
    record ClaimAbandoned(ClaimFacts claim) implements TrackerShape {}

    /**
     * A boundary marker recorded after the newest claim while the task still wears the working
     * label: the truth landed and its index did not.
     *
     * @param boundary the boundary marker whose flip is outstanding; never null
     */
    record IndexLagging(BoundaryKind boundary) implements TrackerShape {}

    /**
     * A combination matching no row above — the shape that keeps the classification total. Never
     * auto-repaired; surfaced with its diagnosis.
     *
     * @param diagnosis what combination was observed, for the operator's report; never null
     */
    record Foreign(String diagnosis) implements TrackerShape {}

    /**
     * The one component responsible for converging this shape to a steady state.
     *
     * @return this shape's recovery owner; never null
     */
    default TrackerRecoveryOwner recoveryOwner() {
        return switch (this) {
            case Ready(), Returned() -> TrackerRecoveryOwner.QUEUE;
            case Claimed ignoredClaimed -> TrackerRecoveryOwner.HOLDER;
            case Parked() -> TrackerRecoveryOwner.HUMAN;
            case Finished(), Revoked() -> TrackerRecoveryOwner.NONE;
            case ClaimPending() -> TrackerRecoveryOwner.REAPER;
            case ClaimAbandoned ignoredAbandoned -> TrackerRecoveryOwner.REAPER;
            case IndexLagging ignoredLagging -> TrackerRecoveryOwner.REAPER;
            case Foreign ignoredForeign -> TrackerRecoveryOwner.NONE;
        };
    }

    /**
     * Whether this is a shape a healthy tracker state rests in — the classification that needs no
     * repair. The three window shapes are exactly the non-steady ones the sweep repairs; {@link
     * Foreign} is not steady either, but has no owner to repair it and is only reported.
     *
     * @return {@code true} for the shapes the steady progression passes through
     */
    default boolean isSteady() {
        return switch (this) {
            case Ready(), Returned(), Parked(), Finished(), Revoked() -> true;
            case Claimed ignoredClaimed -> true;
            case ClaimPending() -> false;
            case ClaimAbandoned ignoredAbandoned -> false;
            case IndexLagging ignoredLagging -> false;
            case Foreign ignoredForeign -> false;
        };
    }
}
