package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Declines every {@code finished} entry observed in a {@code listReady} feed read, shared by
 * {@code serve}'s {@code FeedCycle#poll} and bare auto {@code TakeBareAuto#run} (design D4 of
 * enforce-finish-terminality): {@link FeedPolicy#selectClaimCandidates} only excludes {@code
 * finished} entries defensively from candidate selection — declining the tracker's terminal
 * status back onto them is a tracker side effect, so it belongs to the cycle that owns the
 * tracker port, not to {@code FeedPolicy}'s pure logic.
 *
 * <p>Best-effort per entry (NFR-R2, NFR-R3): a {@link Tracker#declineFinished} call that throws
 * is caught and logged, never propagated, so one failing decline neither aborts the sweep over
 * the remaining entries nor the poll/run cycle that called in — the entry simply stays {@code
 * finished} and is retried naturally on the next poll (task 4.2 of enforce-finish-terminality).
 *
 * <p>Implements FR3, FR4, NFR-R2, NFR-R3, NFR-O1 of enforce-finish-terminality.
 */
public final class FinishedDecline {

    private static final Logger log = LoggerFactory.getLogger(FinishedDecline.class);

    private FinishedDecline() {}

    /**
     * Declines every {@code finished} entry of {@code readyTasks}, skipping the rest. Each
     * {@link Tracker#declineFinished} call is independently best-effort (NFR-R2, NFR-R3): a
     * thrown exception is caught, logged at WARN naming the task ref and cause, and does not stop
     * the sweep from attempting the remaining entries.
     *
     * <p>Implements FR3, FR4, NFR-R2, NFR-R3, NFR-O1 of enforce-finish-terminality.
     *
     * @param tracker the tracker port used for the best-effort {@code declineFinished} write;
     *     never null
     * @param readyTasks the freshly-read {@code listReady} result to sweep; never null
     */
    public static void declineObserved(Tracker tracker, List<ReadyTask> readyTasks) {
        for (ReadyTask task : readyTasks) {
            if (!task.finished()) {
                continue;
            }
            try {
                tracker.declineFinished(task.ref(), DeclineFinishedMessage.forTask(task.ref()));
                log.info(
                        "declined finished task {} observed in the feed",
                        task.ref().id());
            } catch (RuntimeException e) {
                log.warn(
                        "declineFinished failed for task {}; left for the next poll cycle",
                        task.ref().id(),
                        e);
            }
        }
    }
}
