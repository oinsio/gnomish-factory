package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.app.port.git.BranchLocation;
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException;
import com.github.oinsio.gnomish.app.port.git.BranchStateResult;
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.domain.branch.BranchShape;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.status.LiveActivity;
import com.github.oinsio.gnomish.status.Outcome;
import com.github.oinsio.gnomish.status.StatusReport;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Reads a task's {@code .gnomish-task/} files directly from its branch tip via {@code git show
 * <ref>:<path>} — no worktree, no checkout, no local branch creation (FR13, NFR-O1, design D13).
 * Branch lookup is delegated verbatim to {@link TaskBranchLocator} (task 2.6): local →
 * remote-tracking → narrow fetch of exactly {@code gnomish/<task>} → not found, the only
 * permitted side effect (M3).
 *
 * <p>What the tip holds is decided by the shape classifier before anything is rendered (FR16 of
 * harden-task-branch-contract): the located ref is read through {@link RefTipSource} into {@link
 * TipEnvelopeReader}, and the resulting {@link BranchShape} says whether a report can be
 * built at all. A shape whose tip carries no readable envelopes — delivered, bare, or one of the
 * three quarantine shapes — comes back as {@link BranchStateResult.Shaped} for the caller to render
 * calmly, so an unknown {@code "version"} or an unparseable {@code state.json} is a named shape
 * here rather than a thrown {@code UnsupportedStateFileVersionException} (NFR-R2). The classifier
 * holds no claim on this path — {@code status} is a reader, never a tenure — so the epoch fence is
 * inert and {@link BranchShape.StaleEpoch} never arises from it.
 *
 * <p>The resulting {@link StatusReport} is built by the same pure function ({@link
 * StatusReport#build}) manual-run's live status uses, reused verbatim per FR13. Two of its
 * live-only inputs are always absent here because this reader has no live process to observe: the
 * in-flight {@link com.github.oinsio.gnomish.status.Activity} (this is a snapshot of the last
 * recorded round boundary, not "right now", NFR-O1) and {@code attemptLimit} (resolved from the
 * pipeline's stage configuration, which this reader never loads). {@code outcome} and {@code
 * lastEscalation}, by contrast, ARE available — {@code task.json} durably records them (FR5) — so
 * they are threaded through from the branch's own state rather than left null: {@code outcome}
 * reflects {@code task.json}'s {@code outcome} field, null while a visit is in progress (rendering
 * as in-progress/interrupted per the task-inspection spec's "Interrupted task reported honestly"
 * scenario) and the recorded terminal outcome once finished/paused/escalated.
 *
 * <p>Implements FR13, NFR-O1, NFR-R2 of add-git-workflow; FR16 of
 * harden-task-branch-contract.
 */
public final class BranchStateReader {

    private final GitProcessRunner runner;
    private final TaskBranchLocator locator;
    private final TipEnvelopeReader tipEnvelopeReader = new TipEnvelopeReader();

    public BranchStateReader(GitProcessRunner runner) {
        this.runner = runner;
        this.locator = new TaskBranchLocator(runner);
    }

    /**
     * Reads the task branch for {@code taskId} in the clone at {@code cloneDir} and renders its
     * tip state into a {@link StatusReport}.
     *
     * @param cloneDir the working directory of an existing git clone (the {@code --dir} target)
     * @param taskId the tracker's original taskId
     * @return {@link BranchStateResult.Found} with the rendered report, {@link
     *     BranchStateResult.Shaped} when the tip carries no renderable state (delivered, bare,
     *     pre-contract, or a quarantine shape), or {@link BranchStateResult.NotFound} when no
     *     branch exists anywhere for this task
     * @throws BranchLocationUnavailableException if origin could not be asked whether the branch
     *     exists — unavailability is never reported as absence (FR6)
     */
    public BranchStateResult read(Path cloneDir, String taskId) {
        BranchLocation location = locator.locate(cloneDir, taskId);
        return switch (location) {
            case BranchLocation.NotFound ignored -> new BranchStateResult.NotFound();
            // Inspection reports what it knows, never a guess: "origin could not be asked" is not
            // "no such task", and answering NotFound here would tell an operator their branch is
            // gone because their network blinked (FR6).
            case BranchLocation.Unavailable(String reason) ->
                throw new BranchLocationUnavailableException(taskId, reason);
            case BranchLocation.Local local -> readAt(cloneDir, local.ref());
            case BranchLocation.RemoteTracking tracking -> readAt(cloneDir, tracking.ref());
        };
    }

    /**
     * Classifies the tip at {@code ref} and renders it, or answers with the shape when the tip
     * carries nothing to render (FR16).
     */
    private BranchStateResult readAt(Path cloneDir, String ref) {
        BranchTipSource source = new RefTipSource(runner, cloneDir, ref);
        return switch (tipEnvelopeReader.read(source)) {
            case TipEnvelopeRead.NoState(BranchShape shape) -> new BranchStateResult.Shaped(shape);
            case TipEnvelopeRead.Loaded(BranchShape ignored, String taskJson, String stateJson) ->
                new BranchStateResult.Found(readReport(taskJson, stateJson));
        };
    }

    private StatusReport readReport(String taskJson, String stateJson) {
        TaskRecord taskContent = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(taskJson));
        TaskState state = StateJsonMapper.fromDto(StateJsonMapper.readDto(stateJson));

        LiveActivity liveActivity = new LiveActivity(null, taskContent.lastEscalation(), toReportOutcome(taskContent));
        return StatusReport.build(taskContent.context(), state, null, liveActivity);
    }

    /**
     * Maps {@code task.json}'s DTO-level outcome straight to the report model's {@link Outcome},
     * without round-tripping through the domain {@link
     * com.github.oinsio.gnomish.domain.engine.TaskOutcome}: that domain type requires a {@code
     * finalState}, which is redundant here (the report already exposes it via {@link
     * StatusReport}'s own state-derivable fields, same as {@link Outcome}'s own class-level note).
     * {@link Outcome.Escalated} reuses {@code taskContent.lastEscalation()} rather than re-mapping
     * {@code outcomeDto}'s nested report DTO: {@link GitTaskRepository#recordOutcome} always writes
     * an {@code Escalated} outcome's report into the top-level {@code lastEscalation} field in the
     * same commit, so the two are always in lock-step for every {@code task.json} this reader
     * reads, and reusing it avoids duplicating {@code TaskJsonMapper}'s private DTO-to-domain
     * escalation mapping here.
     *
     * <p>{@link Outcome.Aborted#failedAt()} is a structured {@link
     * com.github.oinsio.gnomish.domain.engine.AttemptKey}, but {@code task.json} records only the
     * pre-rendered opaque label ({@code RecordedOutcome.Aborted#failedAt()}, produced by {@code
     * AttemptKey.toString()} at write time, design note on {@link TaskRecord}) — the
     * structured key itself was never durable. The label is carried through in {@code
     * AttemptKey.stage} as the closest faithful placeholder; {@code taskId}/{@code attempt} are
     * fixed markers, not reconstructed data. Callers needing the exact original key must go via
     * {@code usage}'s state-history walk (task 5.5), not this reader.
     */
    private static @Nullable Outcome toReportOutcome(TaskRecord taskContent) {
        RecordedOutcome outcomeDto = taskContent.outcome();
        if (outcomeDto == null) {
            return null;
        }
        return switch (outcomeDto) {
            case RecordedOutcome.Completed ignored -> new Outcome.Completed();
            case RecordedOutcome.Paused paused -> new Outcome.Paused(paused.passedStage());
            case RecordedOutcome.Escalated ignored -> new Outcome.Escalated(requireLastEscalation(taskContent));
            case RecordedOutcome.Aborted aborted ->
                new Outcome.Aborted(
                        new com.github.oinsio.gnomish.domain.engine.AttemptKey(
                                taskContent.context().taskId(), aborted.failedAt(), 0),
                        aborted.cause());
        };
    }

    /**
     * Narrows {@code taskContent.lastEscalation()} to non-null for the {@code Escalated} branch,
     * where the lock-step invariant documented on {@link #toReportOutcome} guarantees it is always
     * present; a null here means the branch's {@code task.json} violates that invariant (FR5).
     */
    private static com.github.oinsio.gnomish.domain.engine.EscalationReport requireLastEscalation(
            TaskRecord taskContent) {
        var lastEscalation = taskContent.lastEscalation();
        if (lastEscalation == null) {
            throw new IllegalStateException("task.json outcome is Escalated but lastEscalation is null for task "
                    + taskContent.context().taskId());
        }
        return lastEscalation;
    }
}
