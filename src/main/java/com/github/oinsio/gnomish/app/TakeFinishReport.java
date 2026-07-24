package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.TakeOutcomeMapper;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.status.LiveActivity;
import com.github.oinsio.gnomish.status.StatusReport;
import com.github.oinsio.gnomish.status.StatusTextRenderer;

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
 * id/title, stage, attempts, decisions, cumulative usage/totals, activity, escalation, last
 * decision) plus one appended line naming the task branch (D11: "the task branch name" and "a
 * link line for the branch" — {@code renderFull} has no branch concept at all, so it is appended
 * here rather than added to the shared renderer). {@code attemptLimit} is passed as {@code null}
 * to {@link StatusReport#build}, mirroring the exact precedent {@link
 * GitResumeContinuation#reportCompleted} sets for a completed task, where {@code
 * state.position()} is always {@link com.github.oinsio.gnomish.domain.engine.Position.PipelineEnd}
 * and there is no current stage to resolve a limit for.
 *
 * <p>Scope note: only the {@code Completed} case is closed here. {@code Escalated} → {@code park}
 * was already closed by {@link TakeEscalationExit} (task 5.8); {@code Paused} → {@code
 * park(CHECKPOINT)} has the identical "decided but never called" gap and remains open for a
 * future task (per {@link TakeEngineExecution}'s own javadoc).
 *
 * <p>Implements FR18, D11 of add-tracker-port.
 */
final class TakeFinishReport {

    private TakeFinishReport() {}

    /**
     * Renders the final report for {@code completed} and finishes {@code ref} on the tracker with
     * it (FR18, D11), then returns the matching {@link TakeResult.Delivered}.
     *
     * <p>Implements FR18, D11 of add-tracker-port.
     *
     * @param completed the fresh engine completion to exit the run with; never null
     * @param context the task's identity and decisions, reflecting all decisions up to this run;
     *     never null
     * @param branchName the task branch's short name, appended as a report line; never null
     * @param tracker the tracker port the finish call is made through; never null
     * @param ref the task's tracker identity; never null
     * @return the {@link TakeResult.Delivered} the finish call was made with; never null
     */
    static TakeResult finish(
            TaskOutcome.Completed completed, TaskContext context, String branchName, Tracker tracker, TaskRef ref) {
        var report = StatusReport.build(context, completed.finalState(), null, LiveActivity.idle());
        String rendered = new StatusTextRenderer().renderFull(report);
        String summary = rendered + "\n" + "Branch: " + branchName;

        tracker.finish(ref, summary);
        return new TakeResult.Delivered(completed.finalState(), summary);
    }
}
