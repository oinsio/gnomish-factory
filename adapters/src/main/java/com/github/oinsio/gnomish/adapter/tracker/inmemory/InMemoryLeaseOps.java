package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult;
import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The lease-maintenance trio of {@link InMemoryTracker} — {@code listOpen}, {@code heartbeat}, {@code
 * removeStaleClaim} (FR4, FR5 of add-claim-heartbeat) — extracted from that class for file size.
 * Operates over the tracker's package-private store/lock/claimClock via {@link
 * InMemoryTracker#withLock(java.util.function.Supplier)};
 * version facts, TTL policy, and staleness judgment stay in core (design D2).
 */
final class InMemoryLeaseOps {

    private final InMemoryTracker tracker;

    /**
     * Race-interleaving hook (FR5): when non-null, {@link #removeStaleClaim} runs it before acquiring
     * the lock — forcing a beat or competing removal between a caller's observation and its under-lock
     * re-check (armed via {@link InMemoryTrackerHarness#armRemoveStaleClaimGate}).
     */
    @Nullable
    Runnable removeStaleClaimGate;

    InMemoryLeaseOps(InMemoryTracker tracker) {
        this.tracker = tracker;
    }

    List<OpenTask> listOpen() {
        return tracker.withLock(() -> tracker.store.entrySet().stream()
                .<OpenTask>mapMulti((entry, consumer) -> {
                    OpenTask open = ClaimLeases.openTask(entry.getKey(), entry.getValue());
                    if (open != null) {
                        consumer.accept(open);
                    }
                })
                .toList());
    }

    HeartbeatResult heartbeat(TaskRef ref, String progressPayload) {
        return tracker.withLock(() -> {
            TrackedTask task = tracker.store.get(ref);
            if (task == null) {
                // A task the tracker no longer holds is the strongest form of "claim gone":
                // there is nothing left to beat. Report the protocol signal, never throw — the
                // lease ops deliberately do NOT go through requireTask (which is right for the
                // v1 operations, where an unknown ref is a programming error), matching fetchTask's
                // "unknown task → Gone, not an exception" and the GitHub adapter's 404-on-listing
                // (the issue itself is gone) mapping, so the reference adapter stays observably
                // symmetric with the live one (FR8, design D7).
                return new HeartbeatResult.ClaimGone();
            }
            return ClaimLeases.beat(task, tracker.claimClock.tick(), progressPayload);
        });
    }

    RemoveStaleClaimResult removeStaleClaim(TaskRef ref, ClaimVersion observedVersion) {
        Runnable gate = removeStaleClaimGate;
        if (gate != null) {
            gate.run();
        }
        return tracker.withLock(() -> {
            TrackedTask task = tracker.store.get(ref);
            if (task == null) {
                // No task ⇒ the claim is already gone: a safe no-op reporting an absent live
                // version (Mismatch(null)), exactly as a claim comment already deleted does on the
                // GitHub adapter — never NoSuchTrackedTaskException, so a foreign reaper observing a
                // vanished task converges rather than mistaking it for infrastructure (NFR-R2, D5).
                return new RemoveStaleClaimResult.Mismatch(null);
            }
            return ClaimLeases.removeIfMatches(task, observedVersion);
        });
    }
}
