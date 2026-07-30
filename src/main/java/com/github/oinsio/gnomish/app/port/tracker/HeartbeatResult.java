package com.github.oinsio.gnomish.app.port.tracker;

/**
 * The outcome of a {@code heartbeat} write (design D1, D7): {@link Beaten} — the
 * claim marker was refreshed in place, carrying the new {@link ClaimVersion} a
 * later observation will read; {@link ClaimGone} — the claim marker is gone
 * (removed by a reaper or taken over), a protocol signal the caller reacts to at
 * its next round boundary.
 *
 * <p>Only these two protocol outcomes are modeled. An infrastructure failure
 * (network, 5xx) is retryable and NOT a result here — it surfaces as an
 * exception, so a transient outage is never confused with a lost claim (FR8,
 * tracker-port spec, "the two are different results, not one exception").
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR5, FR8 of add-claim-heartbeat.
 */
public sealed interface HeartbeatResult permits HeartbeatResult.Beaten, HeartbeatResult.ClaimGone {

    /**
     * The beat succeeded: the claim marker was updated in place and now has
     * {@code version} — the same marker identity with a refreshed last-update
     * fact, observable as a new version by any other instance.
     *
     * @param version the refreshed claim version after the beat; never null
     */
    record Beaten(ClaimVersion version) implements HeartbeatResult {}

    /**
     * The claim marker no longer exists: the claim was removed by a reaper or
     * taken over. This is a protocol signal — the caller's claim is lost and it
     * stops at the nearest round boundary — never an infrastructure error (FR8).
     */
    record ClaimGone() implements HeartbeatResult {}
}
