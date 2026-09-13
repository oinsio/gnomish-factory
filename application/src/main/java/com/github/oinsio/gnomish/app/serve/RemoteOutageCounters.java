package com.github.oinsio.gnomish.app.serve;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The failure/probe/released-claim counts {@link RemoteOutageGate} reports through {@link
 * RemoteOutageHealth} and the {@code remoteOutage} ledger line, extracted so the gate's own file
 * stays a state machine rather than also owning three counters' read-modify-write bookkeeping
 * (process-invariants.md file-size target). This class owns only the counts; it does not know why
 * they changed, or what a caller does with them.
 *
 * <p>Plain {@code int}s would be non-atomic read-modify-write hazards here: {@link #probeFailed()}
 * runs on the feed thread while {@link #claimReleased()} runs on any slot's virtual thread, so each
 * count is its own {@link AtomicInteger}.
 *
 * <p>Implements FR14, NFR-O1, NFR-O3 of add-base-ref-resolution.
 */
final class RemoteOutageCounters {

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger failedProbeCount = new AtomicInteger();
    private final AtomicInteger releasedClaims = new AtomicInteger();

    /** Resets every count for a freshly opened outage: one failure so far, no probes, no releases. */
    void opened() {
        consecutiveFailures.set(1);
        failedProbeCount.set(0);
        releasedClaims.set(0);
    }

    /** Records one more failed probe against the current outage. */
    void probeFailed() {
        failedProbeCount.incrementAndGet();
        consecutiveFailures.incrementAndGet();
    }

    /** Records one claim abandoned with no attempt because the gate was open. */
    void claimReleased() {
        releasedClaims.incrementAndGet();
    }

    /** Clears the consecutive-failure count on close; the probe and released counts stay readable. */
    void closed() {
        consecutiveFailures.set(0);
    }

    int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    int failedProbeCount() {
        return failedProbeCount.get();
    }

    int releasedClaims() {
        return releasedClaims.get();
    }
}
