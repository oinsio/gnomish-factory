package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.logtext.RepeatOccurrence;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * Latch state is per instance and in-memory (NFR-R2 of harden-logging-observability).
 *
 * <p>A latched task is released the poll it stops being finished. The suppressor's only eviction
 * is {@link RepeatSuppressor#recovered}, and this latch's subject — "the tracker still hands this
 * entry back as finished" — has no failure the caller reports the end of, so nothing would ever
 * call it: {@code serve}'s decliner lives as long as the daemon does, and one entry per finished
 * task it ever saw would accumulate for the weeks the daemon runs. The entry's absence from a
 * feed read <em>is</em> the recovery — the decline took effect — so each sweep closes the streaks
 * the read no longer carries. That bounds the latch to the tasks currently finished in the feed,
 * and it is why a one-shot caller can still build its own and throw it away with the run.
 *
 * <p>Implements FR3, FR4, NFR-R2, NFR-R3, NFR-O1 of enforce-finish-terminality; FR12 of
 * harden-logging-observability.
 */
public final class FinishedDecline {

    private static final Logger log = LoggerFactory.getLogger(FinishedDecline.class);

    private final RepeatSuppressor latch;

    /**
     * The tasks currently latched — exactly the suppressor's key set, which it does not expose.
     * Concurrent because the latch is: the sweep runs on one thread today ({@code serve}'s feed
     * cycle, a bare-auto run), and this keeps the class from quietly losing that property if a
     * second caller ever arrives.
     */
    private final Set<String> latched = ConcurrentHashMap.newKeySet();

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
     * task and latched thereafter (FR12); every latched task {@code readyTasks} no longer carries
     * as finished is released at the end of the sweep, so the latch tracks the feed rather than
     * the whole history of the process.
     *
     * <p>Implements FR3, FR4, NFR-R2, NFR-R3, NFR-O1 of enforce-finish-terminality; FR12 of
     * harden-logging-observability.
     *
     * @param tracker the tracker port used for the best-effort {@code declineFinished} write;
     *     never null
     * @param readyTasks the freshly-read {@code listReady} result to sweep; never null
     */
    public void declineObserved(Tracker tracker, List<ReadyTask> readyTasks) {
        Set<String> stillFinished = new HashSet<>();
        for (ReadyTask task : readyTasks) {
            if (!task.finished()) {
                continue;
            }
            stillFinished.add(task.ref().id());
            try {
                tracker.declineFinished(task.ref(), DeclineFinishedMessage.forTask(task.ref()));
                announceDecline(task.ref().id());
            } catch (RuntimeException e) {
                log.warn(
                        OperatorEvent.DECLINE_FINISHED_FAILED.head()
                                + "declineFinished failed for task {}; left for the next poll cycle",
                        task.ref().id(),
                        e);
            }
        }
        releaseCleared(stillFinished);
    }

    /**
     * Closes the streak of every latched task this read no longer carries as finished — the
     * decline took effect — and drops it from the latch. Silent for the ordinary case of a decline
     * that stuck on its first write: the operator was told once that the task was declined, and
     * "it worked" is not a second piece of news. A task the feed kept handing back <em>is</em>
     * news, because it was reported as a decline that was not taking effect, so its clearing gets
     * the one INFO that retires that report.
     *
     * <p>Only reached after a successful feed read: {@code serve} runs the sweep inside its
     * outage retry, so a tracker outage never presents itself here as an empty feed.
     */
    private void releaseCleared(Set<String> stillFinished) {
        for (String taskId : Set.copyOf(latched)) {
            if (stillFinished.contains(taskId)) {
                continue;
            }
            latched.remove(taskId);
            latch.recovered(taskId)
                    .filter(recovery -> recovery.occurrences() > 1)
                    .ifPresent(recovery -> log.info(
                            "finished task {} left the feed after {} declines over {}; the decline took effect",
                            taskId,
                            recovery.occurrences(),
                            recovery.outage()));
        }
    }

    /**
     * The declined-task edge (FR12): the first decline of a task is a state change, the ones after
     * it are the same fact re-observed, and a roll-up naming the count is what an operator needs
     * to see that a decline the factory keeps writing is not taking effect.
     */
    private void announceDecline(String taskId) {
        latched.add(taskId);
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
