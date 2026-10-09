package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.git.PendingVerification;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.status.StatusReport;
import org.jspecify.annotations.Nullable;

/**
 * The per-outcome resume flows of {@link ContainerResumeRunner} — {@code null} (interrupted
 * visit), {@code escalated}, {@code paused}, and {@code completed}. Extracted from {@link
 * ContainerResumeRunner} for file size; the behavior is unchanged, drawing every collaborator from
 * the passed-in runner exactly as {@code ContainerResumeRunner.run} did.
 *
 * <p>Kept in sync with {@link GitResumeContinuation}: both implement the same four outcome arms
 * dispatched by {@link ContainerResumeRunner}/{@link GitResumeRunner} — {@code null} salvages the
 * interrupted round's leftovers (or honours {@code --discard-work}) before continuing; both resolve
 * the escalation through {@link EscalationResume} and land its decision or resumed commit through
 * {@link EscalationResume#land}; both open the gate through {@code TaskRepository.approveCheckpoint}
 * (via {@link CheckpointApproval#continuePause}) before continuing; and {@code completed} prints
 * the same status report with no further engine run. Every continuing arm but {@code null} lands
 * exactly one lifecycle commit before the engine runs. The container arm additionally disposes the
 * kept box before any branch write or round (its clone is behind the park commit); the host arm has
 * none, since its worktree is the branch. Adding or re-meaning an arm on one side alone is the
 * divergence this pair guards against (UX2).
 *
 * <p>Implements FR6, FR17, FR21, FR25 of add-sandbox-core; FR3, FR4, FR5 of make-run-headless; FR4,
 * FR7, FR18 of make-checkpoint-gate-durable; FR18 of supervise-daemon-loops-and-embed-dashboard.
 */
final class ContainerResumeOutcomes {

    private ContainerResumeOutcomes() {}

    /**
     * Outcome {@code null}: an interrupted visit. The box is prepared by {@link
     * ContainerResumePreparation#prepare} — the one preparation {@code take} shares (FR18 of
     * make-checkpoint-gate-durable): a pending snapshot is re-verified rather than salvaged over,
     * {@code --discard-work} disposes whatever survives, otherwise the box is reattached and its
     * leftovers salvaged — then the run is driven through {@link ContainerTerminalDrive}.
     */
    static void resumeFromRecordedPosition(
            ContainerResumeRunner runner,
            SandboxRunSupport support,
            RunOrder order,
            TaskRecord taskJson,
            TaskState state) {
        PendingVerification pending = ContainerResumePreparation.prepare(
                support,
                order.discardWork(),
                state.position(),
                taskJson.context().taskId());
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
     * Outcome {@code escalated}: resolved through {@link EscalationResume#decide} with the operator's
     * {@code --decision} (design D2, D7 of make-run-headless), the decision committed factory-side
     * over bare objects before any environment materializes (FR25, D19 of add-sandbox-core) so the
     * in-box clone carries it from the start; no decision lands the resumed commit instead (FR4; FR7
     * of make-checkpoint-gate-durable), through {@link EscalationResume#land}.
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
        var resumption = new EscalationResume(
                        console, runner.assembly.timeEquipment().clock(), returnPath(order, taskJson))
                .decide(taskJson.context(), escalated, decision);

        // The kept box carried the park, and its clone cannot learn of the park's outcome commit —
        // nor of the decision below — so a round harvested from it would diverge (FR17, design D12
        // of harden-task-branch-contract; the same disposal TakeContainerResumeRunner#appendDecision
        // makes). The next round's box is materialized from the tip that already holds both.
        support.disposeExistingEnvironment();
        EscalationResume.land(support.taskRepository(), taskJson.context().taskId(), resumption, decision);
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
     * A gate (any outcome) or outcome {@code paused}: the resume is the approval — nothing printed,
     * nothing reset, no decision appended (FR5 of make-run-headless). The approval commit lands
     * factory-side first, then the engine continues from the approved state on a box materialized
     * from the tip (FR4, design D2 of make-checkpoint-gate-durable).
     */
    static void resumePaused(
            ContainerResumeRunner runner,
            SandboxRunSupport support,
            RunOrder order,
            TaskRecord taskJson,
            TaskState state) {
        // As in resumeEscalated: the kept box's clone is behind the park's outcome commit and cannot
        // learn of the approval, so it is disposed before the commit and the continuation
        // materializes a fresh box from the tip rather than harvesting a divergent one.
        TaskState approved = CheckpointApproval.continuePause(
                support.taskRepository(),
                taskJson.context().taskId(),
                state,
                order.definition(),
                support::disposeExistingEnvironment);
        ContainerTerminalDrive.run(
                runner.assembly,
                support,
                order,
                taskJson.context(),
                approved,
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
