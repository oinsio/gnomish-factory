package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.logtext.RepeatOccurrence;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
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
 * <p>The decline of one task is announced once and then latched (FR12 of
 * harden-logging-observability): {@code serve} polls the feed every few seconds, and a tracker
 * that keeps handing back an entry already declined would otherwise write the same INFO forever.
 * The first sighting is the news, the repetitions are DEBUG, and the periodic roll-up is what says
 * the decline is not sticking — which is the only version of this an operator has to act on.
 * Latch state is per instance and in-memory (NFR-R2 of harden-logging-observability); it holds one
 * small entry per finished task the run has seen, which is why a one-shot caller builds its own
 * and throws it away with the run.
 *
 * <p>Implements FR3, FR4, NFR-R2, NFR-R3, NFR-O1 of enforce-finish-terminality; FR12 of
 * harden-logging-observability.
 */
public final class FinishedDecline {

    private static final Logger log = LoggerFactory.getLogger(FinishedDecline.class);

    private final RepeatSuppressor latch;

    /** The production wiring: a fresh latch, live for as long as this decliner is. */
    public FinishedDecline() {
        this(RepeatSuppressor.system());
    }

    /**
     * @param latch the per-task announcement latch; a spec drives it on virtual time
     */
    public FinishedDecline(RepeatSuppressor latch) {
        this.latch = latch;
    }

    /**
     * Declines every {@code finished} entry of {@code readyTasks}, skipping the rest. Each
     * {@link Tracker#declineFinished} call is independently best-effort (NFR-R2, NFR-R3): a
     * thrown exception is caught, logged at WARN naming the task ref and cause, and does not stop
     * the sweep from attempting the remaining entries. A successful decline is announced once per
     * task and latched thereafter (FR12).
     *
     * <p>Implements FR3, FR4, NFR-R2, NFR-R3, NFR-O1 of enforce-finish-terminality.
     *
     * @param tracker the tracker port used for the best-effort {@code declineFinished} write;
     *     never null
     * @param readyTasks the freshly-read {@code listReady} result to sweep; never null
     */
    public void declineObserved(Tracker tracker, List<ReadyTask> readyTasks) {
        for (ReadyTask task : readyTasks) {
            if (!task.finished()) {
                continue;
            }
            try {
                tracker.declineFinished(task.ref(), DeclineFinishedMessage.forTask(task.ref()));
                announceDecline(task.ref().id());
            } catch (RuntimeException e) {
                log.warn(
                        "declineFinished failed for task {}; left for the next poll cycle",
                        task.ref().id(),
                        e);
            }
        }
    }

    /**
     * The declined-task edge (FR12): the first decline of a task is a state change, the ones after
     * it are the same fact re-observed, and a roll-up naming the count is what an operator needs
     * to see that a decline the factory keeps writing is not taking effect.
     */
    private void announceDecline(String taskId) {
        switch (latch.failed(taskId, "still finished in the feed")) {
            case RepeatOccurrence.First ignored -> log.info("declined finished task {} observed in the feed", taskId);
            case RepeatOccurrence.Repeat repeat ->
                log.debug("declined finished task {} again ({}x)", taskId, repeat.count());
            case RepeatOccurrence.RollUp rollUp ->
                log.info(
                        "finished task {} is still in the feed after {} declines over {}; the decline is not"
                                + " taking effect",
                        taskId,
                        rollUp.count(),
                        rollUp.elapsed());
        }
    }
}
