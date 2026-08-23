package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TerminalWriteRetry;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;

/**
 * The exhaustive {@link TaskOutcome} -> {@link TakeResult} dispatch shared by {@link
 * TakeEngineExecution#run} (host mode) and {@link TakeContainerEngineExecution#run} (container
 * mode): a fresh {@code Aborted} outcome goes through {@link AbortHandler} with a freshly fetched
 * abort-facts snapshot (task 5.3), a fresh {@code Escalated} outcome is parked through {@link
 * TakeEscalationExit} (task 5.8, FR13, D12), a fresh {@code Completed} outcome is finished through
 * {@link TakeFinishReport} (task 5.11, FR18, D11), and a fresh {@code Paused} outcome is parked as
 * {@code AwaitingHuman(CHECKPOINT)} through {@link TakePauseExit} (FR13, FR18, D12). Both callers
 * reach this only for a non-aborted-by-revocation, non-revoked outcome; host and container mode
 * differ only in the branch name and marker-clearing callback they pass in.
 *
 * <p>Implements FR9, FR12, FR13, FR18, D2, D3, D11, D12 of add-tracker-port; FR1 of
 * add-serve-sandbox-lifecycle.
 */
final class TakeOutcomeDispatch {

    private TakeOutcomeDispatch() {}

    /**
     * Dispatches {@code outcome} to its terminal handler and returns the {@link TakeResult} it
     * produced.
     *
     * @param outcome the engine's terminal outcome for this run; never null
     * @param context the task context the run executed with; never null
     * @param branchName the task's branch name, for {@code Completed}/{@code Paused} reporting
     * @param tracker the tracker port the terminal write is made through; never null
     * @param ref the task's tracker identity; never null
     * @param instanceId this factory instance's identity; never null
     * @param retry the bounded retry policy for the tracker's terminal write; never null
     * @param clearMarker clears the durable "tracker-write pending" marker once a park's write
     *     confirms; a no-op where the caller has no such marker
     * @param abortHandler the infrastructure-abort protocol (task 5.3), applied when {@code outcome}
     *     is {@code Aborted}; never null
     * @param abortThreshold the configured abort-fuse threshold (K) passed to {@code abortHandler};
     *     positive
     * @param parkDelivery the verdict of the pre-park delivery fence (FR4, FR5 of
     *     fix-lifecycle-push): {@code Undelivered} appends its one-line note to the park report the
     *     human reads, {@code Delivered} adds nothing. Container mode records no park lifecycle
     *     commit, so it always passes {@code Delivered} (design D4, NG1)
     * @return the {@link TakeResult} the terminal outcome maps to
     */
    static TakeResult dispatch(
            TaskOutcome outcome,
            TaskContext context,
            String branchName,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId,
            TerminalWriteRetry retry,
            Runnable clearMarker,
            AbortHandler abortHandler,
            int abortThreshold,
            ParkDeliveryVerdict parkDelivery) {
        String replicationNote = parkDelivery.reportNote();
        return switch (outcome) {
            case TaskOutcome.Aborted aborted -> {
                var facts = tracker.fetchTask(ref).abortFacts();
                yield abortHandler.handle(
                        ref, aborted.finalState(), aborted.cause(), facts, abortThreshold, instanceId);
            }
            case TaskOutcome.Escalated escalated ->
                TakeEscalationExit.exit(escalated, tracker, ref, instanceId, retry, clearMarker, replicationNote);
            case TaskOutcome.Completed completed ->
                TakeFinishReport.finish(completed, context, branchName, tracker, ref, instanceId, retry);
            case TaskOutcome.Paused paused ->
                TakePauseExit.finish(
                        paused, context, branchName, tracker, ref, instanceId, retry, clearMarker, replicationNote);
        };
    }
}
