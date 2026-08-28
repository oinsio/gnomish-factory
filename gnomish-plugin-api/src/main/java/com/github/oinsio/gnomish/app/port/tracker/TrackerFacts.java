package com.github.oinsio.gnomish.app.port.tracker;

import com.github.oinsio.gnomish.DoNotMutate;
import org.jspecify.annotations.Nullable;

/**
 * Everything an adapter observed about one task's tracker-side state, and nothing it concluded
 * from it (design D16): the {@link StateLabels} present, the {@link ClaimFacts} footprint, and the
 * newest claim-boundary marker recorded <em>after</em> that footprint's claim marker. Core's
 * classifier maps this triple onto the closed tracker-shape set; adapters never omit, reinterpret,
 * or judge a combination.
 *
 * <p>{@code latestBoundary} is scoped after the newest claim marker on purpose: a boundary that
 * ended an <em>earlier</em> tenure says nothing about the current one, and a task re-claimed after
 * an abort would otherwise read as an index that lags behind a marker it has already passed.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR19, FR12 of harden-task-branch-contract.
 *
 * @param labels the state labels present on the task; never null
 * @param claim the task's claim footprint; never null
 * @param latestBoundary the newest boundary marker recorded after the newest claim marker, or
 *     {@code null} when no boundary follows it
 */
public record TrackerFacts(
        StateLabels labels, ClaimFacts claim, @Nullable BoundaryKind latestBoundary) {

    /**
     * The facts of a task wearing {@code labels} with no claim footprint and no boundary after it.
     *
     * @param labels the state labels present; never null
     * @return the fact triple; never null
     */
    // PIT M5 documented exception: @DoNotMutate for the same JVMTI record-redefinition crash as
    // OpenTask#derivedFacts (hcoles/pitest#1285) — a RUN_ERROR from a broken minion, not a coverage
    // gap. Covered at the ordinary test level by TrackerFactsSpec's two factory scenarios.
    @DoNotMutate
    public static TrackerFacts of(StateLabels labels) {
        return new TrackerFacts(labels, new ClaimFacts.None(), null);
    }

    /**
     * The facts of a task wearing {@code labels} with {@code claim} and no boundary after it.
     *
     * @param labels the state labels present; never null
     * @param claim the claim footprint observed; never null
     * @return the fact triple; never null
     */
    public static TrackerFacts of(StateLabels labels, ClaimFacts claim) {
        return new TrackerFacts(labels, claim, null);
    }
}
