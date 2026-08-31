package com.github.oinsio.gnomish.app.port.tracker;

import org.jspecify.annotations.Nullable;

/**
 * What an adapter observed about a task's claim footprint, as a fact and never as a judgment
 * (design D16): a {@link Live} claim with its holder and {@link ClaimVersion}, a {@link Dead}
 * footprint — a claim marker whose live version is gone, leaving only the last-known holder — or
 * {@link None} at all. Adapters report; the core classifier decides what a combination means, and
 * the observation memory alone decides when it has stood long enough to act on.
 *
 * <p>The three cases are exactly the claim column of the tracker-shape table owned by the {@code
 * claim-heartbeat} capability. Sealed, so a reader switches without a default branch.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR19 of harden-task-branch-contract.
 */
public sealed interface ClaimFacts {

    /**
     * A live claim marker: the tenure is recorded and its version is observable.
     *
     * @param holder the claiming instance's label; never null
     * @param version the live claim's opaque version, carrying the tenure's epoch; never null
     */
    record Live(String holder, ClaimVersion version) implements ClaimFacts {}

    /**
     * A claim footprint with no live version — the marker that anchored the lease is gone while the
     * thread still records who held it. The {@code ClaimAbandoned} input of the reaper's removal.
     *
     * @param lastKnownHolder the instance named by the last claim marker still recorded; never null
     */
    record Dead(String lastKnownHolder) implements ClaimFacts {}

    /** No claim footprint at all — no marker of any tenure is recorded. */
    record None() implements ClaimFacts {}

    /**
     * The live claim's version, or {@code null} for a dead or absent footprint.
     *
     * @return the live version when this is {@link Live}; otherwise null
     */
    default @Nullable ClaimVersion liveVersion() {
        return this instanceof Live live ? live.version() : null;
    }

    /**
     * The instance the footprint names — the live holder, or the last-known one of a dead
     * footprint.
     *
     * @return the holder's label, or {@code null} when there is no footprint at all
     */
    default @Nullable String holder() {
        return switch (this) {
            case Live live -> live.holder();
            case Dead dead -> dead.lastKnownHolder();
            case None ignored -> null;
        };
    }
}
