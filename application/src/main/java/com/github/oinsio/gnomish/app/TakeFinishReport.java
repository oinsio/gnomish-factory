package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.ClaimGuard;
import com.github.oinsio.gnomish.app.take.FinishEffect;
import com.github.oinsio.gnomish.app.take.FinishTransition;
import com.github.oinsio.gnomish.app.take.TakeOutcomeMapper;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TerminalWriteRetry;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.status.ReportPlane;
import com.github.oinsio.gnomish.status.StatusReport;
import com.github.oinsio.gnomish.status.StatusTextRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes the gap {@link TakeOutcomeMapper} deliberately leaves open for a fresh {@code Completed}
 * outcome (task 5.11, design D11): {@link TakeOutcomeMapper#map} produces a placeholder
 * ("Task completed.") summary and never calls the tracker — this class renders the real,
 * operator-facing final report from the existing {@link StatusReport} model (no new aggregation
 * model, design D11) and performs the actual {@link Tracker#finish(TaskRef, String)} call, so a
 * take run ends identically with or without a TTY, mirroring how {@link TakeEscalationExit} closes
 * the equivalent gap for {@code Escalated} (task 5.8).
 *
 * <p>The report is {@link StatusTextRenderer#renderFull(StatusReport)}'s full text block (task
 * id/title, stage, attempts, decisions, cumulative usage/totals, escalation, last decision) plus one appended line naming the task branch (D11: "the task branch name" and "a
 * link line for the branch" — {@code renderFull} has no branch concept at all, so it is appended
 * here rather than added to the shared renderer). The block is rendered on the
 * {@link ReportPlane#COMMENT} plane: its prose is the factory's own and is published as it stands,
 * while the untrusted fields it quotes — the task title, an escalation detail, a denial's locator —
 * take the comment exit's inline shape inside it. Fencing the assembled report instead would label
 * the factory's own lines "untrusted machine output", which is the alternative design D7 rejects.
 * The report is built from the final state alone, mirroring {@link
 * GitResumeContinuation#reportCompleted} for a completed task, where {@code state.position()} is
 * always {@link com.github.oinsio.gnomish.domain.engine.Position.PipelineEnd}.
 *
 * <p>Scope note: only the {@code Completed} case is closed here. {@code Escalated} → {@code park}
 * was already closed by {@link TakeEscalationExit} (task 5.8); {@code Paused} → {@code
 * park(CHECKPOINT)} is closed by {@link TakePauseExit}.
 *
 * <p>Implements FR18, D11 of add-tracker-port.
 */
final class TakeFinishReport {

    private static final Logger log = LoggerFactory.getLogger(TakeFinishReport.class);

    private TakeFinishReport() {}

    /**
     * Renders the final report for {@code completed} and finishes the order's task on the tracker with
     * it (FR18, D11), then returns the matching {@link TakeResult.Delivered}.
     *
     * <p>The {@code tracker.finish} write is git-unfenced, so it is preceded by {@link
     * ClaimGuard#stillOurs} (FR7, design D6 of add-claim-heartbeat): when the claim was reaped or
     * taken over mid-run, the finish is skipped with a WARN rather than overwriting the new holder's
     * tracker state — the run still returns the mapped {@link TakeResult.Delivered} (the branch
     * carries the delivered outcome; the residual TOCTOU costs at most a stray label).
     *
     * <p>The git-unfenced {@code tracker.finish} write is wrapped in {@code retry} so a tracker
     * outage at the finish line is retried with backoff for the bounded hold-the-slot period (FR10,
     * D10, NFR-R3 of add-claim-heartbeat); on give-up the run still returns {@link
     * TakeResult.Delivered} and logs an ERROR naming the unreconciled finish, which
     * reconcile-on-resume completes later. The caller has already recorded the outcome commit and
     * owns the cleanup, so both branch-side steps are empty — a fresh write, which is what keeps it
     * from spending a probe read (FR10).
     *
     * <p>Implements FR18, D11 of add-tracker-port; FR7, FR10, D10, NFR-R3 of add-claim-heartbeat.
     *
     * @param completed the fresh engine completion to exit the run with; never null
     * @param context the task's identity and decisions, reflecting all decisions up to this run;
     *     never null
     * @param branchName the task branch's short name, appended as a report line; never null
     * @param order the take order whose task is finished: its tracker, the task's identity, and
     *     this instance's identity for the pre-write claim check; never null
     * @param retry the bounded terminal-write retry the finish runs under — the slot wiring's,
     *     built on the composition root's time source (FR18 of
     *     supervise-daemon-loops-and-embed-dashboard); never null
     * @return the {@link TakeResult.Delivered} the finish call was made with; never null
     */
    static TakeResult finish(
            TaskOutcome.Completed completed,
            TaskContext context,
            String branchName,
            TakeOrder order,
            TerminalWriteRetry retry) {
        return finish(completed, context, branchName, order, retry, new FinishTransition.Fresh(() -> {}, () -> {}));
    }

    /**
     * As the overload above, but with the completion's branch-side steps supplied: the {@code
     * Completed} outcome commit as the durable intent, and the cleanup commit plus workspace
     * disposal as the destructive tail behind the confirmed finish (FR9, FR10 of
     * harden-task-branch-contract). A recovered completion — a tip already recording {@code
     * Completed} — probes the tracker before re-driving the write.
     *
     * @param transition the completion's branch-side steps; never null
     */
    static TakeResult finish(
            TaskOutcome.Completed completed,
            TaskContext context,
            String branchName,
            TakeOrder order,
            TerminalWriteRetry retry,
            FinishTransition transition) {
        var report = StatusReport.build(context, completed.finalState(), null, null);
        String rendered = new StatusTextRenderer(ReportPlane.COMMENT).renderFull(report);
        String summary = rendered + "\n" + "Branch: " + branchName;

        new FinishEffect(order, summary, retry, transition, log).drive();
        return new TakeResult.Delivered(completed.finalState(), summary);
    }
}
