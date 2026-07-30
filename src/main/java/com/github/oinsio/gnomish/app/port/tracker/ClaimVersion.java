package com.github.oinsio.gnomish.app.port.tracker;

import java.time.Instant;

/**
 * The opaque version of a claim lease: the claim-marker identity ({@code
 * markerId}) paired with its last-update fact ({@code updatedAt}) — design D2's
 * {@code (commentId, updatedAt)} sketch, kept tracker-agnostic at the port
 * layer. Core treats it as an opaque token compared only by content: a beat
 * refreshes {@code updatedAt} so a later observation reads a different version,
 * while the {@code markerId} — the lease anchor — stays stable across beats.
 *
 * <p>The name is deliberately not {@code commentId}: {@code markerId} is
 * whatever a tracker uses to identify the single claim marker (a GitHub comment
 * id, some other tracker's token), so the port stays agnostic to any one
 * tracker's shape. Core never parses either field; it only compares versions and
 * measures staleness on its own monotonic clock, never against {@code updatedAt}
 * (design D2 — no cross-host clock arithmetic).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR5 of add-claim-heartbeat.
 *
 * @param markerId the claim-marker identity, stable across beats; never blank
 * @param updatedAt the marker's last-update fact, refreshed by every beat; never null
 */
public record ClaimVersion(String markerId, Instant updatedAt) {

    public ClaimVersion {
        markerId = requireNonBlank(markerId);
    }

    /**
     * Fails fast on a blank {@code markerId}: a lease with no anchor identity
     * cannot be beaten, observed, or removed (FR5). Kept as an explicit static
     * method rather than inline in the compact constructor: PIT's record filter
     * suppresses all mutations inside a record's canonical constructor, which
     * would silently exempt this validation from the 100% mutation gate.
     */
    private static String requireNonBlank(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("ClaimVersion.markerId must not be blank");
        }
        return value;
    }
}
