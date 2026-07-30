package com.github.oinsio.gnomish.app.port.tracker;

import org.jspecify.annotations.Nullable;

/**
 * The outcome of {@code removeStaleClaim} (design D5 sketch: {@code Removed |
 * Mismatch(currentFacts)}): {@link Removed} — the observed version still matched,
 * the dead claim marker was cleaned up and the task returned to {@code Ready};
 * {@link Mismatch} — the observed version no longer matched, so nothing was
 * removed and the current live facts are reported instead.
 *
 * <p>{@link Mismatch} makes concurrent removals converge without coordination
 * (NFR-R2): when a racing reaper or a live beat has changed the claim since the
 * caller's observation, the operation is a safe no-op rather than an error. Its
 * {@code currentVersion} is the version the caller should now see — {@code null}
 * when the claim marker is already gone (another reaper removed it, or the task
 * is no longer {@code Working}).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR4, FR5 of add-claim-heartbeat.
 */
public sealed interface RemoveStaleClaimResult permits RemoveStaleClaimResult.Removed, RemoveStaleClaimResult.Mismatch {

    /**
     * The stale claim was removed: the holder-transition marker was recorded, the
     * dead claim marker deleted, and the task returned to {@code Ready}. The
     * caller does NOT hold the task afterwards — it must claim by the ordinary
     * lease (FR4).
     */
    record Removed() implements RemoveStaleClaimResult {}

    /**
     * Nothing was removed: the observed version no longer matched the live claim,
     * so the operation is a safe no-op reporting the current facts.
     *
     * @param currentVersion the live claim version now, or {@code null} when the
     *     claim marker is already gone
     */
    record Mismatch(@Nullable ClaimVersion currentVersion) implements RemoveStaleClaimResult {}
}
