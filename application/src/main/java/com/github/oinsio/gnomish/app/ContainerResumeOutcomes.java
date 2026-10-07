package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.git.PendingVerification;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.status.StatusReport;
import java.time.Clock;
import org.jspecify.annotations.Nullable;

/**
 * The per-outcome resume flows of {@link ContainerResumeRunner} — {@code null} (interrupted
 * visit), {@code escalated}, {@code paused}, and {@code completed}. Extracted from {@link
 * ContainerResumeRunner} for file size; the behavior is unchanged, drawing every collaborator from
 * the passed-in runner exactly as {@code ContainerResumeRunner.run} did.
 *
 * <p>Kept in sync with {@link GitResumeContinuation}: both implement the same four outcome arms
 * dispatched by {@link ContainerResumeRunner}/{@link GitResumeRunner} — {@code null} salvages the
 * interrupted round's leftovers (or honours {@code --discard-work}) before continuing, both resolve
 * the escalation through {@link EscalationResume} and continue a pause without a prompt, and {@code
 * completed} builds and prints the same status report with no further engine run. The container
 * arm additionally disposes the kept box before any branch write or round (its clone is behind
 * the park commit); the host arm has none, since its worktree is the branch. Adding or re-meaning
 * an arm on one side alone is the divergence this pair guards against (UX2).
 *
 * <p>Implements FR6, FR17, FR21, FR25 of add-sandbox-core; FR3, FR4, FR5 of make-run-headless.
 */
final class ContainerResumeOutcomes {

    private ContainerResumeOutcomes() {}

    /**
     * Outcome {@code null}: an interrupted visit. A snapshot commit unrecorded in {@code
     * state.json} is an interrupted verification (FR21) — no salvage runs, the round is complete
     * on the branch. Otherwise the environment is reattached (start stopped box, recreate over a
     * surviving volume, or fresh clone) and uncommitted leftovers are salvaged in-box; {@code
     * --discard-work} instead disposes whatever survives so the next materialize seeds a fresh
     * clone at the recorded tip.
     */
    static void resumeFromRecordedPosition(
            ContainerResumeRunner runner,
            SandboxRunSupport support,
            RunOrder order,
            TaskRecord taskJson,
            TaskState state) {
        PendingVerification pending = support.pendingVerification().orElse(null);
        if (order.discardWork()) {
            support.disposeExistingEnvironment();
        } else {
            String stage = stageToReattach(state.position());
            if (stage != null) {
                // Reattach now (start stopped box / recreate over volume / fresh clone) so both the
                // salvage below and same-box verification of a pending snapshot have a live box.
                support.reattachFor(stage);
                if (pending == null) {
                    support.salvageLeftovers(taskJson.context().taskId());
                }
            }
        }
        ContainerTerminalDrive.run(
                runner.assembly,
                support,
                order,
                taskJson.context(),
                state,
                ManualResumeLawBinding.of(order.cloneDir(), taskJson.pin(), taskJson.baseCommit()),
                pending);
    }

    /**
     * The stage whose box a resume reattaches before salvage or same-box verification: the stage the
     * position names, and at a gate the {@code manual} stage that passed — the box of the stage whose
     * round the gate's commit recorded (FR1 of make-checkpoint-gate-durable). Past the pipeline's end
     * there is no stage and nothing to reattach. Shared by this class and {@link
     * TakeContainerResumeRunner#resumeWithoutDecision}, the two media of the same decision.
     *
     * @param position the recorded position the resume starts from; never null
     * @return the stage to reattach for, or {@code null} at the pipeline end
     */
    static @Nullable String stageToReattach(Position position) {
        return switch (position) {
            case Position.AtStage(String stage) -> stage;
            case Position.AwaitingApproval(String gate) -> gate;
            case Position.PipelineEnd() -> null;
        };
    }

    /**
     * Outcome {@code escalated}: resolved through {@link EscalationResume#decide} with the operator's
     * {@code --decision} (design D2, D7 of make-run-headless), the decision committed factory-side
     * over bare objects before any environment materializes (FR25, D19 of add-sandbox-core) so the
     * in-box clone carries it from the start; no decision continues on the reset state alone (FR4).
     *
     * @throws DecisionRequiredException for a {@code DecisionNeeded} report and no decision: the
     *     question is restated, nothing is written, no box is touched (FR4 of make-run-headless)
     */
    static void resumeEscalated(
            ContainerResumeRunner runner,
            SandboxRunSupport support,
            RunOrder order,
            TaskRecord taskJson,
            TaskState state,
            @Nullable String decision) {
        EscalationReport report = taskJson.lastEscalation();
        if (report == null) {
            throw new InternalErrorException("task \"" + taskJson.context().taskId()
                    + "\" has outcome \"escalated\" but no lastEscalation recorded in task.json — cannot resume");
        }
        var escalated = new TaskOutcome.Escalated(state, report);

        var console = runner.assembly.dialogConsole();
        var resumption = new EscalationResume(console, Clock.systemUTC(), returnPath(order, taskJson))
                .decide(taskJson.context(), escalated, decision);

        // The kept box carried the park, and its clone cannot learn of the park's outcome commit —
        // nor of the decision below — so a round harvested from it would diverge (FR17, design D12
        // of harden-task-branch-contract; the same disposal TakeContainerResumeRunner#appendDecision
        // makes). The next round's box is materialized from the tip that already holds both.
        support.disposeExistingEnvironment();
        if (decision != null) {
            // One commit carries the decision and the attempts reset (NFR-R1 of make-run-headless).
            support.taskRepository()
                    .appendDecision(
                            taskJson.context().taskId(),
                            resumption.context().decisions().getLast(),
                            resumption.state());
        }
        ContainerTerminalDrive.run(
                runner.assembly,
                support,
                order,
                resumption.context(),
                resumption.state(),
                ManualResumeLawBinding.of(order.cloneDir(), taskJson.pin(), taskJson.baseCommit()),
                null);
    }

    /**
     * Outcome {@code paused}: the resume is the confirmation — nothing printed, nothing reset, no
     * decision appended (FR5 of make-run-headless) — on a box materialized from the tip.
     */
    static void resumePaused(
            ContainerResumeRunner runner,
            SandboxRunSupport support,
            RunOrder order,
            TaskRecord taskJson,
            TaskState state) {
        // As in resumeEscalated: the kept box's clone is behind the park's outcome commit, so the
        // continuation materializes a fresh box from the tip rather than harvesting a divergent one.
        support.disposeExistingEnvironment();
        ContainerTerminalDrive.run(
                runner.assembly,
                support,
                order,
                taskJson.context(),
                state,
                ManualResumeLawBinding.of(order.cloneDir(), taskJson.pin(), taskJson.baseCommit()),
                null);
    }

    /** Outcome {@code completed}: the same final status summary as the host path, no engine run. */
    static void reportCompleted(ContainerResumeRunner runner, TaskRecord taskJson, TaskState state) {
        var report = StatusReport.build(taskJson.context(), state, null, null);
        // The run's console owner (FR5, FR6 of harden-untrusted-text-sinks), mirroring the host
        // path's own summary print.
        runner.assembly.dialogConsole().print(runner.statusRenderer.renderFull(report) + ConsoleIO.LINE_END);
    }

    /** Where this task resumes from: the {@code --dir} clone and the task id (FR1 of make-run-headless). */
    private static TerminalOutcomeRender.ReturnPath returnPath(RunOrder order, TaskRecord taskJson) {
        return new TerminalOutcomeRender.ReturnPath(
                order.cloneDir(), taskJson.context().taskId());
    }
}
