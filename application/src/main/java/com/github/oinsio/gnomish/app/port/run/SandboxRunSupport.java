package com.github.oinsio.gnomish.app.port.run;

import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.app.port.git.PendingVerification;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Everything a sandboxed run's use cases ask of the box its task lives in, as one capability set
 * (FR3, FR5, FR6, FR12, FR21, FR25 of add-sandbox-core). The container runners drive a task through
 * its lifecycle — create or reattach, run rounds, salvage, settle a terminal boundary — without
 * naming Docker, the git subprocess, or the bare-object reader that realize any of it.
 *
 * <p>The single realization today is the container support bundle in the composition root, built
 * per run once the task branch exists. Introduced by task 4.4 of split-into-modules (FR12b, D12):
 * the runners previously held that bundle directly, which made every one of them an adapter holder
 * and would have sunk the whole container run path into {@code :bootstrap}.
 *
 * <p>Implements FR12b of split-into-modules; FR3, FR5, FR6, FR12, FR21, FR25 of add-sandbox-core.
 */
public interface SandboxRunSupport {

    /** The task-lifecycle repository the run creates, decides, and records outcomes through. */
    TaskRepository taskRepository();

    /** The persistence rounds commit through: strict in-box commits with a best-effort push (FR5, FR21, FR22). */
    AttemptPersistence persistence();

    /**
     * The adapter bundle the run assembly swaps in for the host defaults.
     *
     * @param pendingVerification an interrupted verification to consume without an agent re-run
     *     (FR21), or {@code null} when the resume found none
     */
    SandboxRunPieces pieces(@Nullable PendingVerification pendingVerification);

    /** The engine workspace of a sandboxed run: the attempt-commit ref, never a host path (D15). */
    Workspace workspace();

    /**
     * The interrupted verification sitting unrecorded at the task branch tip, if any (FR21, D15).
     *
     * @return the pending verification, or empty when the tip is not a snapshot commit
     */
    Optional<PendingVerification> pendingVerification();

    /**
     * Makes the task's box live again for {@code stage} — start a stopped one, recreate over a
     * surviving volume, or seed a fresh clone — so salvage and same-box verification have somewhere
     * to run (FR6).
     */
    void reattachFor(String stage);

    /** Commits the interrupted round's uncommitted leftovers in-box, as-is and unrecorded (FR6). */
    void salvageLeftovers(String taskId);

    /** Disposes a kept environment left by a previous instance ({@code --discard-work}, FR6). */
    void disposeExistingEnvironment();

    /** Prunes objects a dead instance left labelled, keeping this task's own (FR11, NFR-R2). */
    void sweepOrphans();

    /** Completed terminal boundary (D19): dispose, then record the outcome and cleanup commits. */
    void completeAndDispose(TaskState finalState);

    /** Aborted terminal boundary (D19): record on the last harvested tip; the box is kept as evidence. */
    void recordAborted(TaskOutcome.Aborted outcome);

    /** Keep semantics for a run that ended without disposing: stop the box, retain volume and network. */
    void keepStopped();

    /** The last durably committed state on the task branch (FR17). */
    TaskState readFinalState();

    /**
     * The recorded state at the branch tip, or the initial state at {@code firstStage} when no round
     * ever persisted one — a task killed during its very first round has only the creation commit
     * (FR6).
     */
    TaskState readStateOrInitial(String firstStage);

    /** The task record at the branch tip — context, outcome, escalation (FR17). */
    TaskRecord readTaskJson();
}
