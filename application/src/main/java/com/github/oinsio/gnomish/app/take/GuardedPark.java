package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import org.slf4j.Logger;

/**
 * Shared guard-then-retry-then-park orchestration for the fresh-outcome exits that call {@link
 * Tracker#park(TaskRef, ParkReason, String)} under a {@link TerminalWriteRetry} bound —
 * {@code TakeEscalationExit} ({@code Escalated}) and {@code TakePauseExit} ({@code Paused},
 * {@link ParkReason#CHECKPOINT}). Both precede the git-unfenced park with {@link
 * ClaimGuard#stillOurs} (FR7, design D6 of add-claim-heartbeat): when the claim was reaped or
 * taken over mid-run, the park is skipped with a WARN rather than overwriting the new holder's
 * tracker state. When the claim still holds, the park is retried with backoff for the bounded
 * hold-the-slot period (FR10, D10, NFR-R3 of add-claim-heartbeat); {@code onConfirmed} runs once
 * (and only once) the park confirms landed, clearing the branch's "tracker-write pending" marker
 * so a later resume reads the park as settled. On give-up the marker is left set and an ERROR
 * names the unreconciled park; reconcile-on-resume completes the deferred park later.
 *
 * <p>Implements FR13, D12 of add-tracker-port; FR7, FR10, D10, NFR-R3 of add-claim-heartbeat.
 */
public final class GuardedPark {

    private GuardedPark() {}

    /**
     * Parks {@code (ref, reason, report)} through {@code retry} when {@link
     * ClaimGuard#stillOurs} still holds, running {@code onConfirmed} once the park lands; skips
     * the write with a WARN when the claim moved, and logs an ERROR naming {@code kind} when the
     * retry bound elapses without confirmation.
     *
     * <p>Implements FR13, D12 of add-tracker-port; FR7, FR10, D10, NFR-R3 of add-claim-heartbeat.
     *
     * @param tracker the tracker port the park call is made through; never null
     * @param ref the task's tracker identity; never null
     * @param instanceId this factory instance's identity, for the pre-write claim check; never null
     * @param reason the park reason recorded on the tracker; never null
     * @param report the operator-facing report text recorded with the park; never null
     * @param retry the bounded terminal-write retry the park is made through; never null
     * @param onConfirmed cleared-marker action run once the park confirms landed; never null
     * @param log the caller's logger, so log lines are attributed to the calling class; never null
     * @param kind a short label distinguishing the two callers' log lines (e.g. {@code "park"},
     *     {@code "checkpoint park"}); never null
     */
    public static void attempt(
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId,
            ParkReason reason,
            String report,
            TerminalWriteRetry retry,
            Runnable onConfirmed,
            Logger log,
            String kind) {
        if (ClaimGuard.stillOurs(tracker, ref, instanceId)) {
            if (retry.confirm(() -> tracker.park(ref, reason, report)) == TerminalWriteRetry.Result.CONFIRMED) {
                onConfirmed.run();
            } else {
                log.error(
                        "{} of {} could not be written before the retry bound elapsed; the branch keeps the "
                                + "outcome as tracker-write pending and a later resume will reconcile the deferred "
                                + "park",
                        kind,
                        ref.id());
            }
        } else {
            log.warn("skipping {} of {}: claim is no longer held by this instance", kind, ref.id());
        }
    }
}
