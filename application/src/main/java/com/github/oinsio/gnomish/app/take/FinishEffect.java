package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import com.github.oinsio.gnomish.app.terminal.EffectObservation;
import com.github.oinsio.gnomish.app.terminal.TerminalEffect;
import com.github.oinsio.gnomish.app.terminal.TerminalEffectDrive;
import org.slf4j.Logger;

/**
 * The completion as one intent→effect→receipt flow (FR10, design D5 of harden-task-branch-contract):
 * the {@code Completed} outcome commit and its delivery to origin are the durable intent, {@code
 * tracker.finish} is the effect, and the cleanup commit that removes {@code .gnomish-task/} from the
 * tip is both the receipt and the destructive last step — removing the envelope takes the pending
 * marker with it, so no separate receipt commit exists.
 *
 * <p>An unconfirmed finish therefore leaves the tip recording {@code Completed} with its envelope
 * intact: the {@code CompletedUncleaned} shape, whose recovery probes the tracker and finishes what
 * is left, never re-entering the engine (FR9).
 *
 * <p>Implements FR9, FR10 of harden-task-branch-contract; FR18, D11 of add-tracker-port.
 *
 * @param tracker the tracker port the finish is written through; never null
 * @param ref the task's tracker identity; never null
 * @param instanceId this factory instance's identity, for the pre-write claim check; never null
 * @param summary the operator-facing final report the finish carries; never null
 * @param retry the bounded terminal-write retry the finish is made through; never null
 * @param transition the completion's branch-side steps — fresh or recovered; never null
 * @param log the caller's logger, so log lines stay attributed to the calling class; never null
 */
public record FinishEffect(
        Tracker tracker,
        TaskRef ref,
        InstanceId instanceId,
        String summary,
        TerminalWriteRetry retry,
        FinishTransition transition,
        Logger log)
        implements TerminalEffect {

    /**
     * Drives this completion to its end: a fresh one records its intent first, a recovered one
     * probes the tracker before re-driving the write.
     */
    public void drive() {
        if (transition instanceof FinishTransition.Fresh) {
            TerminalEffectDrive.deliverFresh(this);
        } else {
            TerminalEffectDrive.redeliver(this);
        }
    }

    @Override
    public void recordIntent() {
        if (transition instanceof FinishTransition.Fresh(var intent, var ignoredCleanup)) {
            intent.run();
        }
    }

    /**
     * A tracker already reporting the task as finished carries this finish. Anything else —
     * including a tracker that cannot be asked — re-drives, which the find-then-upsert write makes
     * safe (FR11).
     */
    @Override
    public EffectObservation observeAtTarget() {
        try {
            var task = tracker.fetchTask(ref);
            return task.finished() || task.state() instanceof TrackerTaskState.Finished
                    ? EffectObservation.LANDED
                    : EffectObservation.ABSENT;
        } catch (RuntimeException e) {
            log.warn("could not verify whether the finish of {} already landed", ref.id(), e);
            return EffectObservation.UNDETERMINED;
        }
    }

    @Override
    public boolean deliver() {
        if (!ClaimGuard.stillOurs(tracker, ref, instanceId)) {
            log.warn("skipping finish of {}: claim is no longer held by this instance", ref.id());
            return false;
        }
        if (retry.confirm(() -> tracker.finish(ref, summary)) == TerminalWriteRetry.Result.CONFIRMED) {
            return true;
        }
        log.error(
                "finish of {} could not be written before the retry bound elapsed; the branch records the "
                        + "delivery and a later resume will reconcile the deferred finish",
                ref.id());
        return false;
    }

    @Override
    public void recordReceipt() {
        // The completion's receipt is its cleanup commit: removing .gnomish-task/ takes the pending
        // marker with it, so the receipt and the destructive step are one commit.
    }

    @Override
    public void runDestructiveStep() {
        transition.cleanup().run();
    }
}
