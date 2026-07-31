package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;

/**
 * The cheap "claim still ours" pre-write check that fences the unfenced tracker writes (FR7,
 * design D6): the git non-fast-forward push is the hard fence, but the tracker writes git does not
 * fence — {@code park}, {@code finish} — are preceded by this conditional read so a zombie whose
 * claim was reaped or taken over does not overwrite the new holder's tracker state. The read goes
 * through the adapter's ETag conditional cache, so an unchanged re-read is a free 304 (NFR-P1).
 *
 * <p>"Still ours" is exactly the same verdict {@link RevocationCheckingAttemptPersistence} uses at
 * the round boundary: {@link TrackerTaskState.Working} held by this instance's own {@link
 * InstanceId}. Any other state — a foreign holder, a park, a return to {@code Ready}, a close, a
 * finish — means the claim moved and the caller must skip the write, not fight the move. The
 * residual TOCTOU window may still cost a stray label/comment, never data corruption, and converges
 * with the new holder's next write (FR7).
 *
 * <p>Only the fresh-outcome terminal writes are guarded through this helper. {@code release} on the
 * revocation-salvage path ({@link RevocationHandler}) is deliberately NOT routed here: that path is
 * only ever reached once the round boundary — or the heartbeat's claim-loss flag — already proved
 * the claim is not ours, so a "still ours" pre-check there would always fail and silently drop the
 * FR15 salvage-release step; the GitHub adapter's {@code release} is a no-op anyway, so an unguarded
 * release on that path is harmless in production.
 *
 * <p>Implements FR7 of add-claim-heartbeat.
 */
public final class ClaimGuard {

    private ClaimGuard() {}

    /**
     * Reports whether {@code ref} is still {@code Working} held by {@code instanceId} right now,
     * via one {@link Tracker#fetchTask(TaskRef)} read.
     *
     * <p>Implements FR7 of add-claim-heartbeat.
     *
     * @param tracker the tracker port the conditional read is made through; never null
     * @param ref the task whose claim is being confirmed; never null
     * @param instanceId this factory instance's identity, compared against the reported holder;
     *     never null
     * @return {@code true} when the task is {@code Working} held by {@code instanceId}, {@code
     *     false} for every other state or holder
     */
    public static boolean stillOurs(Tracker tracker, TaskRef ref, InstanceId instanceId) {
        return tracker.fetchTask(ref).state() instanceof TrackerTaskState.Working(String holder)
                && holder.equals(instanceId.value());
    }
}
