package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import com.github.oinsio.gnomish.app.take.DeclineFinishedMessage;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The explicit-mode ({@code take <ref>}) disposition matrix (proposal FR9, UX2; design D2, D3):
 * given an already-fetched {@link TrackerTask}, dispatches per its {@link TrackerTaskState} —
 * {@code Ready} claims and works it (fresh or resumed) unless also {@code finished} (reopened by a
 * human), which refuses via the decline protocol instead (FR5); {@code AwaitingHuman} refuses
 * without mutating the tracker, {@code Working} (held by another instance) enters the {@link
 * TakeTakeover} confirmation path (task 6.2 of add-claim-heartbeat, FR6), {@code Finished}/{@code
 * Gone} skip. The operator mandate overrides the readiness criterion and abort backoff (FR9)
 * simply by never consulting either: this class only reads the state {@code fetchTask} already
 * reported. The same omission pierces the WIP limit for {@code Ready} tasks (FR8 of
 * add-factory-serve): neither {@link com.github.oinsio.gnomish.app.take.OpenFrontGate} nor any
 * open-front count is consulted here, so the mandate is unconditional for a {@code Ready} target;
 * {@code AwaitingHuman} keeps the existing refusal, {@code Working} keeps the takeover protocol.
 *
 * <p>Short-ref expansion (`42`, `#42`) and CLI argument parsing/Spring wiring are later concerns
 * (tasks 5.13/5.14, not built here) — {@link #dispose} takes an already-resolved {@link TaskRef};
 * this class is the plain, constructor-injectable entry point command wiring calls into.
 *
 * <p>Implements FR9, UX2, D2, D3 of add-tracker-port; FR6 of add-claim-heartbeat; FR8 of
 * add-factory-serve; FR5 of enforce-finish-terminality.
 */
final class TakeDisposition {

    private static final Logger log = LoggerFactory.getLogger(TakeDisposition.class);

    private final TakeClaimAndWork claimAndWork;
    private final TakeTakeover takeover;

    /**
     * @param wiring the slot's equipment (D2 of introduce-slot-wiring), fixed for the whole take
     *     invocation: the claim/resume paths' assembly, task git, worktrees root, MDC key, abort
     *     fuse, credential names, container seam, claim tenure and trusted base tier; never null
     * @param takeoverFlag whether {@code --takeover} authorized a headless {@code Working} takeover
     *     (task 6.2, FR6): bypasses the {@code confirmation} seam
     * @param confirmation the pre-claim takeover-confirmation seam (task 6.2, FR6, design D9); never null
     * @param clock the run's clock, used only to render the display-only last-beat age in the
     *     takeover facts (design D9); never null
     */
    TakeDisposition(SlotWiring wiring, boolean takeoverFlag, TakeoverConfirmation confirmation, Clock clock) {
        this.claimAndWork = new TakeClaimAndWorkFactory(wiring).forSlot();
        this.takeover = new TakeTakeover(claimAndWork, confirmation, takeoverFlag, clock);
    }

    /**
     * Dispatches on the claimed task's {@code state()} per the explicit-mode disposition matrix (FR9, UX2).
     *
     * <p>Implements FR9, UX2, D2, D3 of add-tracker-port.
     *
     * @param order the take order {@code take <ref>} is acting on: the already-fetched task fact
     *     set, the tracker and this instance's identity, and the run order — whose {@code --base}
     *     override is ignored when resuming an existing branch (D4: a tracker task always starts
     *     at the pipeline's first stage on a fresh claim, only {@code --base} chooses where that
     *     start commit is) and whose {@code --discard-work} is meaningful only when resuming;
     *     never null
     * @return the {@link TakeResult} of the disposition
     */
    public TakeResult dispose(TakeOrder order) {
        TaskRef ref = order.ref();
        return switch (order.trackerTask().state()) {
            case TrackerTaskState.Ready ignored
            when order.trackerTask().finished() -> refuseFinished(ref, order.tracker());
            case TrackerTaskState.Ready ignored -> claimAndWork.claimAndWork(order);
            case TrackerTaskState.Working working -> takeover.take(order, working.holder());
            case TrackerTaskState.AwaitingHuman awaitingHuman -> refuseParked(awaitingHuman.reason());
            case TrackerTaskState.Finished ignored ->
                new TakeResult.Skipped(
                        UntrustedText.factory("Task " + ref.id() + " is already done (Finished) — nothing to take."));
            case TrackerTaskState.Gone ignored ->
                new TakeResult.Skipped(UntrustedText.factory("Task " + ref.id() + " is closed or does not exist."));
        };
    }

    /** A reopened-finished {@code Ready} task: refuses via the decline protocol, never claiming (FR5). */
    private static TakeResult refuseFinished(TaskRef ref, Tracker tracker) {
        log.info("declining reopened finished task {} refused under an explicit take <ref> mandate", ref.id());
        tracker.declineFinished(ref, DeclineFinishedMessage.forTask(ref));
        return new TakeResult.Skipped(
                UntrustedText.factory("Task " + ref.id() + " is already finished — nothing to take."));
    }

    private static TakeResult refuseParked(ParkReason reason) {
        String returnPath =
                switch (reason) {
                    case ESCALATION ->
                        "A pending question is recorded; reply in the tracker and move the task back to ready.";
                    case CHECKPOINT ->
                        "A manual checkpoint is recorded; move the task back to ready to continue past it.";
                    case INFRA ->
                        "An environment or pipeline problem is recorded; fix it, then move the task back to ready"
                                + " to retry.";
                };
        // The original park report text is not retrievable here (the Tracker port exposes no "read
        // report" operation), so UX2/FR9 are met by naming the reason and return path honestly.
        return new TakeResult.Skipped(
                UntrustedText.factory("Task is parked awaiting a human (" + reason + "). " + returnPath));
    }
}
