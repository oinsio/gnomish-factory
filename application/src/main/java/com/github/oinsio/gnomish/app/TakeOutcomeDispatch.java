package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.RecoveryCause;
import com.github.oinsio.gnomish.app.take.AbortFuse;
import com.github.oinsio.gnomish.app.take.AbortTrigger;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TerminalTransitions;
import com.github.oinsio.gnomish.app.take.TerminalWriteRetry;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;

/**
 * The slot's exhaustive {@link TaskOutcome} -> {@link TakeResult} dispatch, the shared terminal
 * path of {@link TakeEngineExecution#run} (host mode) and {@link TakeContainerEngineExecution#run}
 * (container mode): a fresh {@code Aborted} outcome goes through {@link AbortFuse#handle} with a
 * freshly fetched abort-facts snapshot (task 5.3), a fresh {@code Escalated} outcome is parked
 * through {@link TakeEscalationExit} (task 5.8, FR13, D12), a fresh {@code Completed} outcome is
 * finished through {@link TakeFinishReport} (task 5.11, FR18, D11), and a fresh {@code Paused}
 * outcome is parked as {@code AwaitingHuman(CHECKPOINT)} through {@link TakePauseExit} (FR13, FR18,
 * D12). Both callers reach this only for a non-aborted-by-revocation, non-revoked outcome; host and
 * container mode differ only in the branch name and the terminal transitions they pass in.
 *
 * <p>Built by {@link SlotWiring#outcomeDispatch()}, the one construction site (design D22 of
 * supervise-daemon-loops-and-embed-dashboard), once per run it is handed to: the slot-fixed part —
 * the terminal-write retry derived from the slot's time equipment and the slot's abort fuse — is
 * held as fields, the run-fixed part — the
 * outcome and the run's {@link TerminalTransitions} — is the per-call job (design D7 of
 * collapse-composition-roots). <b>Lifetimes:</b> the dispatch holds nothing shorter-lived than the
 * slot — the retry is an immutable record over the slot's time equipment, the fuse an immutable
 * record over the slot's abort handler — and the transitions are valid for one {@link #dispatch}
 * call and never retained.
 *
 * <p>The retry is a member of the protocol, not a decorator around it: it is not transparent — on
 * give-up it changes the outcome, leaving a note and the pending marker — so it is a step of the
 * terminal write, not a concern wrapped around the dispatch.
 *
 * <p>ADR 0010's three answers: (a) the deletion test — without either member the dispatch cannot
 * map every outcome (the {@code Aborted} arm uses the fuse, the three others the retry); (b) the
 * behavior is {@link #dispatch}; (c) the name has existed since collapse-composition-roots.
 *
 * <p>Implements FR9, FR12, FR13, FR18, D2, D3, D11, D12 of add-tracker-port; FR1 of
 * add-serve-sandbox-lifecycle; FR18, FR22 of supervise-daemon-loops-and-embed-dashboard.
 */
final class TakeOutcomeDispatch {

    private final TerminalWriteRetry retry;
    private final AbortFuse abortFuse;

    /**
     * Package-private: {@link SlotWiring#outcomeDispatch()} is the one construction site.
     *
     * @param retry the bounded retry policy for the tracker's terminal write; never null
     * @param abortFuse the infrastructure-abort protocol (task 5.3) and its threshold (K), applied
     *     when the outcome is {@code Aborted}; never null
     */
    TakeOutcomeDispatch(TerminalWriteRetry retry, AbortFuse abortFuse) {
        this.retry = retry;
        this.abortFuse = abortFuse;
    }

    /**
     * Dispatches {@code outcome} to its terminal handler and returns the {@link TakeResult} it
     * produced.
     *
     * @param outcome the engine's terminal outcome for this run; never null
     * @param context the task context the run executed with; never null
     * @param branchName the task's branch name, for {@code Completed}/{@code Paused} reporting
     * @param order the take order the run executed: the tracker the terminal write is made
     *     through, the task's identity and this instance's identity; never null
     * @param transitions the run's terminal transitions; valid for this call only, never retained
     * @return the {@link TakeResult} the terminal outcome maps to
     */
    TakeResult dispatch(
            TaskOutcome outcome,
            TaskContext context,
            String branchName,
            TakeOrder order,
            TerminalTransitions transitions) {
        return switch (outcome) {
            case TaskOutcome.Aborted aborted -> {
                var facts = order.tracker().fetchTask(order.ref()).abortFacts();
                yield abortFuse.handle(
                        order.ref(),
                        aborted.finalState(),
                        aborted.cause(),
                        facts,
                        order.instanceId(),
                        AbortTrigger.engineAborted(RecoveryCause.INSTANCE_CRASH));
            }
            case TaskOutcome.Escalated escalated ->
                TakeEscalationExit.exit(escalated, order, retry, transitions.park());
            case TaskOutcome.Completed completed ->
                TakeFinishReport.finish(completed, context, branchName, order, retry, transitions.finish());
            case TaskOutcome.Paused paused ->
                TakePauseExit.finish(paused, context, branchName, order, retry, transitions.park());
        };
    }
}
