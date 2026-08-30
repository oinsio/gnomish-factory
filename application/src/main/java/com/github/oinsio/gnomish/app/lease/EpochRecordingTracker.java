package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.AbortRecord;
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts;
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult;
import com.github.oinsio.gnomish.app.port.tracker.HumanReply;
import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import java.util.List;

/**
 * Keeps a {@link ClaimEpochBook} in step with the tenures this instance actually holds, by watching
 * the one place a tenure can begin or end: the tracker port itself (FR13).
 *
 * <p>A tenure begins when {@code claim} answers {@link ClaimResult.Acquired} — the epoch is recorded
 * before the caller can make its first write, so no commit of the tenure goes out unstamped.
 *
 * <p>It ends at whichever comes first: the caller drops the claim ({@code release}), or a beat
 * reports the claim gone. Both mean the same thing — this instance no longer holds the task — and
 * recording it is what stops a superseded holder from stamping an epoch it no longer owns, the very
 * write the fence exists to catch.
 *
 * <p>A terminal write — {@code recordAbort}, {@code park}, {@code finish} — deliberately does NOT
 * end the tenure here, even though it ends the claim on the tracker. Those transitions still have
 * branch work behind them: the intent→effect→receipt protocol runs its receipt and its destructive
 * step (the park receipt commit, the finish cleanup commit) only once the tracker write has
 * confirmed, and those commits belong to the tenure that made them. Forgetting the epoch at the
 * tracker write left them unstamped — outside the fence, so a zombie's late cleanup commit could
 * not classify as {@code StaleEpoch}. The run-scoped end is where the tenure actually finishes:
 * {@code TakeClaimAndWork.dispatchAfterClaim}'s {@code finally}, the single claim-holding choke
 * point, which forgets it in the same breath that stops the beats.
 *
 * <p>Wrapped around the live tracker at the two commands that claim — {@code take} and {@code serve}
 * — rather than threaded down to each writer: every claim of a run necessarily passes through this
 * one port instance, so the book cannot drift from the claims it describes. Read-only commands
 * ({@code board}, {@code status}) never claim, so they are left unwrapped.
 *
 * <p>Implements FR13 of harden-task-branch-contract.
 */
public final class EpochRecordingTracker implements Tracker {

    private final Tracker delegate;
    private final ClaimEpochBook book;

    /**
     * @param delegate the live tracker every call is forwarded to; never null
     * @param book the instance's tenure record this decorator keeps current; never null
     */
    public EpochRecordingTracker(Tracker delegate, ClaimEpochBook book) {
        this.delegate = delegate;
        this.book = book;
    }

    @Override
    public ClaimResult claim(TaskRef ref, String instanceId) {
        ClaimResult result = delegate.claim(ref, instanceId);
        if (result instanceof ClaimResult.Acquired(var epoch)) {
            book.issued(ref.id(), epoch);
        }
        return result;
    }

    @Override
    public void release(TaskRef ref) {
        delegate.release(ref);
        book.ended(ref.id());
    }

    // The three terminal writes keep the tenure recorded on purpose — their receipt and cleanup
    // commits still have to carry it. See the class javadoc.
    @Override
    public void recordAbort(TaskRef ref, AbortRecord record) {
        delegate.recordAbort(ref, record);
    }

    @Override
    public void park(TaskRef ref, ParkReason reason, String report) {
        delegate.park(ref, reason, report);
    }

    @Override
    public void finish(TaskRef ref, String summary) {
        delegate.finish(ref, summary);
    }

    @Override
    public HeartbeatResult heartbeat(TaskRef ref, String progressPayload) {
        HeartbeatResult result = delegate.heartbeat(ref, progressPayload);
        if (result instanceof HeartbeatResult.ClaimGone) {
            book.ended(ref.id());
        }
        return result;
    }

    @Override
    public List<ReadyTask> listReady(int limit) {
        return delegate.listReady(limit);
    }

    @Override
    public TrackerTask fetchTask(TaskRef ref) {
        return delegate.fetchTask(ref);
    }

    @Override
    public List<HumanReply> collectDecisions(TaskRef ref) {
        return delegate.collectDecisions(ref);
    }

    @Override
    public void declineFinished(TaskRef ref, String message) {
        delegate.declineFinished(ref, message);
    }

    @Override
    public void recordProgress(TaskRef ref) {
        delegate.recordProgress(ref);
    }

    @Override
    public void acknowledgeDecision(TaskRef ref, String decisionText) {
        delegate.acknowledgeDecision(ref, decisionText);
    }

    @Override
    public void postNote(TaskRef ref, String text) {
        delegate.postNote(ref, text);
    }

    @Override
    public List<OpenTask> listOpen() {
        return delegate.listOpen();
    }

    @Override
    public RemoveStaleClaimResult removeStaleClaim(TaskRef ref, ClaimFacts observedClaim) {
        return delegate.removeStaleClaim(ref, observedClaim);
    }

    @Override
    public RepairIndexResult repairIndex(TaskRef ref, TrackerFacts observedFacts) {
        return delegate.repairIndex(ref, observedFacts);
    }
}
