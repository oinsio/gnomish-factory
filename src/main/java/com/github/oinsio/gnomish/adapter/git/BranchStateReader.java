package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonContent;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto;
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
 * <p>{@code task.json} and {@code state.json} are version-gated by the same mechanism resume uses
 * ({@link TaskJsonMapper#readDto}/{@link StateJsonMapper#readDto}, both built on the shared
 * version gate of task 1.4): an unknown {@code "version"} throws {@code
 * UnsupportedStateFileVersionException} naming the file and version, propagated to this reader's
 * caller rather than caught here — the same "let it reach the CLI boundary" idiom the gate's own
 * javadoc documents.
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
 * <p>Implements FR13, NFR-O1, NFR-R2 of add-git-workflow.
 */
public final class BranchStateReader {

    private static final String TASK_JSON_PATH = ".gnomish-task/task.json";
    private static final String STATE_JSON_PATH = ".gnomish-task/state.json";

    private final GitProcessRunner runner;
    private final TaskBranchLocator locator;

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
     * @return {@link BranchStateResult.Found} with the rendered report, or {@link
     *     BranchStateResult.NotFound} when no branch exists anywhere for this task
     * @throws com.github.oinsio.gnomish.adapter.git.state.UnsupportedStateFileVersionException if
     *     either state file carries an unsupported {@code "version"}
     * @throws BranchStateFileMissingException if the branch was found but a state file is absent
     *     at its tip (e.g. a {@code Completed} task's cleanup commit already removed {@code
     *     .gnomish-task/})
     */
    public BranchStateResult read(Path cloneDir, String taskId) {
        BranchLocation location = locator.locate(cloneDir, taskId);
        return switch (location) {
            case BranchLocation.NotFound ignored -> new BranchStateResult.NotFound();
            case BranchLocation.Local local -> new BranchStateResult.Found(readReport(cloneDir, local.ref()));
            case BranchLocation.RemoteTracking tracking ->
                new BranchStateResult.Found(readReport(cloneDir, tracking.ref()));
        };
    }

    private StatusReport readReport(Path cloneDir, String ref) {
        TaskJsonContent taskContent =
                TaskJsonMapper.fromDto(TaskJsonMapper.readDto(show(cloneDir, ref, TASK_JSON_PATH)));
        TaskState state = StateJsonMapper.fromDto(StateJsonMapper.readDto(show(cloneDir, ref, STATE_JSON_PATH)));

        LiveActivity liveActivity = new LiveActivity(null, taskContent.lastEscalation(), toReportOutcome(taskContent));
        return StatusReport.build(taskContent.context(), state, null, liveActivity);
    }

    private String show(Path cloneDir, String ref, String filePath) {
        GitCommandResult result = runner.run(cloneDir, "show", ref + ":" + filePath);
        if (result.exitCode() != 0) {
            throw new BranchStateFileMissingException(ref, filePath, result.stderr());
        }
        return result.stdout();
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
     * pre-rendered opaque label ({@code TaskOutcomeDto.Aborted#failedAt()}, produced by {@code
     * AttemptKey.toString()} at write time, design note on {@link TaskJsonContent}) — the
     * structured key itself was never durable. The label is carried through in {@code
     * AttemptKey.stage} as the closest faithful placeholder; {@code taskId}/{@code attempt} are
     * fixed markers, not reconstructed data. Callers needing the exact original key must go via
     * {@code usage}'s state-history walk (task 5.5), not this reader.
     */
    private static @Nullable Outcome toReportOutcome(TaskJsonContent taskContent) {
        TaskOutcomeDto outcomeDto = taskContent.outcome();
        if (outcomeDto == null) {
            return null;
        }
        return switch (outcomeDto) {
            case TaskOutcomeDto.Completed ignored -> new Outcome.Completed();
            case TaskOutcomeDto.Paused paused -> new Outcome.Paused(paused.passedStage());
            case TaskOutcomeDto.Escalated ignored -> new Outcome.Escalated(requireLastEscalation(taskContent));
            case TaskOutcomeDto.Aborted aborted ->
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
            TaskJsonContent taskContent) {
        var lastEscalation = taskContent.lastEscalation();
        if (lastEscalation == null) {
            throw new IllegalStateException("task.json outcome is Escalated but lastEscalation is null for task "
                    + taskContent.context().taskId());
        }
        return lastEscalation;
    }
}
