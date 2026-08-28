package com.github.oinsio.gnomish.app.port.tracker;

import com.github.oinsio.gnomish.domain.engine.port.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A thin {@link Tracker}-port decorator that observes every call, shared by every daemon caller
 * — feed, heartbeat, reaper — rather than counted at any one caller's boundary (design D12): in
 * Full the feed legally stops polling while heartbeat and reaper keep calling, so wrapping only
 * the feed would go blind to an outage under saturation, exactly when D9's tracker escalation
 * rule must fire. One instance is constructed around the daemon's single {@link Tracker} and
 * handed to every caller, so all three share the same counters.
 *
 * <p>Every delegated call is timed the same way: a normal return sets {@link #lastSuccessAt()} to
 * {@code clock.now()} and resets {@link #consecutiveFailures()} to zero; a thrown {@link
 * RuntimeException} increments {@link #consecutiveFailures()} and is rethrown completely
 * unchanged — this decorator is transparent, never altering a caller's error handling (FR8).
 *
 * <p>Implements FR8, D12 of add-serve-observability.
 */
public final class TrackerHealthTracker implements Tracker {

    private final Tracker delegate;
    private final Clock clock;
    private volatile @Nullable Instant lastSuccessAt;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /**
     * @param delegate the underlying tracker every call is forwarded to; never null
     * @param clock the time source read on each success to set {@link #lastSuccessAt()}; never
     *     null
     */
    public TrackerHealthTracker(Tracker delegate, Clock clock) {
        this.delegate = delegate;
        this.clock = clock;
    }

    /**
     * Returns the last time any delegated call returned normally, or {@code null} if none has
     * ever succeeded.
     *
     * @return the last success instant, or {@code null}
     */
    public @Nullable Instant lastSuccessAt() {
        return lastSuccessAt;
    }

    /**
     * Returns the number of delegated calls that have thrown in a row since the last success.
     *
     * @return the current failure streak; never negative
     */
    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    @Override
    public List<ReadyTask> listReady(int limit) {
        return call(() -> delegate.listReady(limit));
    }

    @Override
    public TrackerTask fetchTask(TaskRef ref) {
        return call(() -> delegate.fetchTask(ref));
    }

    @Override
    public List<HumanReply> collectDecisions(TaskRef ref) {
        return call(() -> delegate.collectDecisions(ref));
    }

    @Override
    public ClaimResult claim(TaskRef ref, String instanceId) {
        return call(() -> delegate.claim(ref, instanceId));
    }

    @Override
    public void release(TaskRef ref) {
        call(() -> delegate.release(ref));
    }

    @Override
    public void park(TaskRef ref, ParkReason reason, String report) {
        call(() -> delegate.park(ref, reason, report));
    }

    @Override
    public void finish(TaskRef ref, String summary) {
        call(() -> delegate.finish(ref, summary));
    }

    @Override
    public void recordAbort(TaskRef ref, AbortRecord record) {
        call(() -> delegate.recordAbort(ref, record));
    }

    @Override
    public void recordProgress(TaskRef ref) {
        call(() -> delegate.recordProgress(ref));
    }

    @Override
    public void acknowledgeDecision(TaskRef ref, String decisionText) {
        call(() -> delegate.acknowledgeDecision(ref, decisionText));
    }

    @Override
    public void postNote(TaskRef ref, String text) {
        call(() -> delegate.postNote(ref, text));
    }

    @Override
    public void declineFinished(TaskRef ref, String message) {
        call(() -> delegate.declineFinished(ref, message));
    }

    @Override
    public List<OpenTask> listOpen() {
        return call(delegate::listOpen);
    }

    @Override
    public HeartbeatResult heartbeat(TaskRef ref, String progressPayload) {
        return call(() -> delegate.heartbeat(ref, progressPayload));
    }

    @Override
    public RemoveStaleClaimResult removeStaleClaim(TaskRef ref, ClaimFacts observedClaim) {
        return call(() -> delegate.removeStaleClaim(ref, observedClaim));
    }

    @Override
    public RepairIndexResult repairIndex(TaskRef ref, TrackerFacts observedFacts) {
        return call(() -> delegate.repairIndex(ref, observedFacts));
    }

    /** Runs a void delegate call through the same success/failure accounting as {@link #call}. */
    private void call(Runnable op) {
        call(() -> {
            op.run();
            return null;
        });
    }

    /**
     * Runs one delegate call, recording success (resets the streak, stamps {@link
     * #lastSuccessAt}) or failure (increments the streak) before propagating the outcome
     * unchanged — a normal return or the original {@link RuntimeException}.
     */
    private <T> T call(Supplier<T> op) {
        T result;
        try {
            result = op.get();
        } catch (RuntimeException failure) {
            consecutiveFailures.incrementAndGet();
            throw failure;
        }
        lastSuccessAt = clock.now();
        consecutiveFailures.set(0);
        return result;
    }
}
