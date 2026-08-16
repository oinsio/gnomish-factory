package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-adapter monotonic source for {@link ClaimMarker} identities and version
 * timestamps (design D2, D15). A stable {@code markerId} is minted once per claim
 * from a rising sequence ({@code "claim-" + n}); {@code updatedAt} is drawn from a
 * separate strictly advancing counter mapped onto {@link Instant#EPOCH}, so every
 * write yields a version distinct from the one before it — the deterministic,
 * randomness-free advance the heartbeat contract and PIT reproducibility both need
 * (never {@link Instant#now()}, which can repeat for two fast writes).
 *
 * <p>Not a wall clock: core measures staleness on its own monotonic clock, never
 * against {@code updatedAt} (design D2). Minting always happens under the adapter's
 * store lock; the {@link AtomicLong}s make that explicit and safe regardless.
 *
 * <p>Implements FR5 of add-claim-heartbeat.
 */
final class ClaimClock {

    private final AtomicLong markerSequence = new AtomicLong();
    private final AtomicLong versionSequence = new AtomicLong();

    /** Mints a fresh marker with a new stable identity and the next version instant, held by {@code holder}. */
    ClaimMarker mint(String holder) {
        return new ClaimMarker("claim-" + markerSequence.incrementAndGet(), tick(), holder, null);
    }

    /** The next strictly-advancing version instant. */
    Instant tick() {
        return Instant.EPOCH.plusNanos(versionSequence.incrementAndGet());
    }
}
