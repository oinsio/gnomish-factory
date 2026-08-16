package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The in-memory adapter's claim marker: this reference adapter's stand-in for a
 * GitHub claim comment (design D2, D15). It anchors a lease with a stable {@code
 * markerId} identity, carries the last-update fact {@code updatedAt} that every
 * beat advances, remembers the {@code holder} so a stale-claim removal can name
 * the dead instance, and keeps the latest heartbeat {@code payload} (the progress
 * text a live claim comment would hold).
 *
 * <p>Not part of the {@link com.github.oinsio.gnomish.app.port.tracker.Tracker}
 * port: an in-memory-adapter-only implementation detail, held on {@link
 * TrackedTask} and projected to the port's opaque {@link ClaimVersion} via {@link
 * #version()}. {@code markerId} stays constant across beats; only {@code
 * updatedAt} (and {@code payload}) move on.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR5 of add-claim-heartbeat.
 *
 * @param markerId the lease anchor, stable across beats; never blank
 * @param updatedAt the last-update fact, advanced by every beat; never null
 * @param holder the claiming instance's identifier, named when the claim is reaped
 * @param payload the latest heartbeat progress text, or {@code null} before the first beat
 */
record ClaimMarker(
        String markerId,
        Instant updatedAt,
        String holder,
        @Nullable String payload) {

    /**
     * The opaque port-level version of this marker: its identity paired with its last-update fact.
     *
     * <p>PIT M5 documented exception (build.gradle has the full rationale): {@code @DoNotMutate}
     * because PIT's Gregor engine crashes its own minion JVM (RUN_ERROR, not a real test gap)
     * mutating this record's methods on JDK 17+ (hcoles/pitest#1285, a JVMTI RedefineClasses
     * restriction on NestHost/NestMembers/Record attributes — not fixable via PIT config). Otherwise
     * fully covered by {@code InMemoryTrackerLeaseSpec}, which asserts the projected {@link
     * ClaimVersion} on every lease/heartbeat/reap scenario.
     */
    @DoNotMutate
    ClaimVersion version() {
        return new ClaimVersion(markerId, updatedAt);
    }

    /** A new marker with the same identity and holder but an advanced {@code updatedAt} and fresh {@code payload}. */
    ClaimMarker beat(Instant newUpdatedAt, String newPayload) {
        return new ClaimMarker(markerId, newUpdatedAt, holder, newPayload);
    }
}
