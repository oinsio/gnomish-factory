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

    /**
     * Runs one startup pass of the ownership-based sweep-lifecycle policy over this project's
     * labelled objects (FR6, FR7 of add-serve-sandbox-lifecycle) — no longer the name-snapshot
     * pruning this method carried before (FR11, NFR-R2 of add-sandbox-core, removed with the
     * {@code sweep(liveKeys)} contract).
     *
     * <p>A one-shot run holds no project-wide claim listing, so the pass evaluates with no
     * liveness verdict: {@code tracked} objects of other tasks degrade to skipped-no-verdict and
     * are never touched, while this run's own {@code mode=manual} objects are governed by age
     * alone. Hygiene, not the task — a missing Docker runtime or any other failure of the pass is
     * logged and swallowed, never a failed run.
     */
    void sweepOrphans();

    /**
     * Hands the run's environments the denial read position recorded at the task branch tip, so a
     * resume that reattaches to a surviving egress guard reports its own rounds' denials instead of
     * replaying every denial the guard still holds (FR5 of fix-denial-report-attachment).
     *
     * <p>Best-effort and always safe to call: a branch with no recorded cursor — a fresh run, a
     * task whose rounds ran host-side — is a no-op, and a cursor that does not name the live denial
     * source is dropped by the environment rather than applied.
     */
    void restoreDenialCursor();

    /**
     * Completed terminal boundary (D19): dispose the box, then record the {@code Completed} outcome
     * commit — the durable intent of the completion sequence. The cleanup commit is NOT part of it:
     * it is the destructive last step, run through {@link #finishCleanup()} once the terminal
     * tracker write has landed (FR10 of harden-task-branch-contract). A run with no tracker to write
     * to calls the two back to back.
     */
    void completeAndDispose(TaskState finalState);

    /**
     * The {@code Completed} cleanup commit, removing {@code .gnomish-task/} from the branch tip —
     * the destructive last step of the completion sequence, built factory-side from bare objects
     * with no live box required (FR15, D19 of add-sandbox-core; FR10 of
     * harden-task-branch-contract). Idempotent: an already-cleaned tip is left alone.
     */
    void finishCleanup();

    /**
     * Clears the branch's durable "terminal write pending" marker once the outcome's tracker write
     * has confirmed (FR10 of harden-task-branch-contract) — the container twin of the host park's
     * receipt, so a container park settles instead of reading as orphaned on every later resume.
     */
    void confirmTerminalWrite();

    /** Aborted terminal boundary (D19): record on the last harvested tip; the box is kept as evidence. */
    void recordAborted(TaskOutcome.Aborted outcome);

    /** Keep semantics for a run that ended without disposing: stop the box, retain volume and network. */
    void keepStopped();

    /**
     * Records a park ({@code Escalated} or {@code Paused}) on the task branch, factory-side over bare
     * objects, carrying the durable "terminal write pending" marker — the park's intent, written
     * before the tracker write it precedes (FR10, design D12 of harden-task-branch-contract).
     *
     * <p>Before this existed a container park recorded nothing at all, so the human's escalation
     * answer was read against a branch with no park on it and every return re-parked the task. The
     * kept box is stopped by then, so this commit is the last factory-side commit until that box is
     * disposed (FR17 of harden-task-branch-contract).
     *
     * @param outcome the park to record; {@code Escalated} or {@code Paused}
     */
    void recordPark(TaskOutcome outcome);

    /** The last durably committed state on the task branch (FR17). */
    TaskState readFinalState();

    /**
     * The recorded state at the branch tip, or the initial state at {@code firstStage} when no round
     * ever persisted one — a task killed during its very first round has only the creation commit
     * (FR6).
     */
    TaskState readStateOrInitial(String firstStage);

    /**
     * The task record at the branch tip — context, outcome, escalation (FR17).
     *
     * @throws com.github.oinsio.gnomish.gitobjects.MissingObjectException when the tip carries no
     *     {@code .gnomish-task/task.json} at all: the shape a {@code Completed} cleanup commit
     *     leaves behind (FR15 of add-git-workflow), which a resume reads as delivered-and-cleaned
     *     rather than as a fault (design D8 of add-serve-sandbox-lifecycle)
     */
    TaskRecord readTaskJson();

    /**
     * Runs the tracker-take revocation salvage protocol (FR15 of add-tracker-port; FR1 of
     * add-serve-sandbox-lifecycle): commits any uncommitted round leftovers in-box, then
     * best-effort pushes the task branch — the sandboxed equivalent of {@code
     * TaskSalvage#salvage} followed by {@code TaskBranchGit#pushBestEffort}, folded into one
     * method because a sandboxed run has no worktree path for those host-shaped ports to push
     * from. The box itself is left exactly as the claim loss found it — running or not — so the
     * next sweep tick classifies it unowned and stops it (design D3); this method disposes
     * nothing.
     *
     * @param taskId the task being salvaged, for error context; never blank
     */
    void revocationSalvageAndPush(String taskId);
}
