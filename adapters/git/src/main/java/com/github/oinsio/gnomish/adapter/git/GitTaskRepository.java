package com.github.oinsio.gnomish.adapter.git;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.app.port.TrackerWrite;
import com.github.oinsio.gnomish.app.port.git.BasePin;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.atomicfile.AtomicFileWriter;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The git realization of {@link TaskRepository} (design D1): creates the task branch and worktree
 * and writes the first {@code task.json} commit at start, appends resume {@link Decision}s
 * (resetting {@code outcome} to null in the same commit, FR5/D9), opens a checkpoint gate
 * ({@link #approveCheckpoint}, FR3 of make-checkpoint-gate-durable), consumes a recorded outcome
 * without a decision ({@link #resumeFrom}, FR7), and records the terminal {@link
 * TaskOutcome} — populating {@code lastEscalation} for {@code Escalated} — at completion or parking.
 * Shares the branch with {@link GitAttemptPersistence}, split by file (D3): this class owns {@code
 * task.json} exclusively. Worktree setup is an internal concern (not on the port): every method
 * resolves the deterministic worktree via {@link TaskWorktreeManager#ensureWorktree}, idempotent
 * for both fresh start and resume.
 *
 * <p>Strict port: any failure to durably record a lifecycle event is thrown as {@link
 * GitTaskRepositoryException}. The {@code Completed} envelope removal is {@link #finishCleanup},
 * the destructive last step of the completion sequence (FR15, design D4; FR10 of
 * harden-task-branch-contract); prior commits stay reachable as the audit trail (M4).
 *
 * <p>Both envelopes are written through the shared {@link AtomicFileWriter} (design D10 of
 * harden-task-branch-contract) — {@code task.json} here, {@code state.json} through {@link
 * StateFileWrite} — so no reader ever observes a partially written envelope.
 *
 * <p>The STARTED commit carries the initial {@code state.json} beside {@code task.json} (FR3,
 * design D2 of harden-task-branch-contract), so a run that dies before its first round completes
 * still leaves a readable branch — this class writes that one file and {@link
 * GitAttemptPersistence} owns every later write of it.
 *
 * <p>Kept in sync with {@link GitObjectsTaskRepository}: both media run the same task-lifecycle
 * write protocol, and in particular both take the task's start point as an already-peeled {@code
 * ObjectId} — the caller's single peel of its law binding — resolve no base <em>name</em> of their
 * own, verify the object is a commit this repository holds, and record that same commit as {@code
 * baseCommit} beside the {@code (ref, kind, rule)} pin (FR15, D12 of add-base-ref-resolution,
 * revised 2026-09-10); and both make no commit from {@link #recordOutcome} when the rewritten
 * {@code task.json} is byte for byte what the tip already carries, deciding that through {@link
 * CommittedTaskJson#carries} before any commit step (design D8 of make-run-headless, task 2.6);
 * and the three outcome-clearing writes ({@code appendDecision}, {@code approveCheckpoint},
 * {@code resumeFrom}) land the same {@code task.json}/{@code state.json} fields in one commit and
 * refuse on the same tip conditions — the fields composed by {@link OutcomeClearingTaskJson}, the
 * refusals decided by {@link CheckpointApprovalCheck} and {@link ResumedWriteCheck} (design D6 of
 * make-checkpoint-gate-durable) — and remove the consumed requests under {@code decisions/} in that
 * same commit, the removal owned by {@link ConsumedRequestRemoval} (an index removal staged after
 * the envelope here, a tree edit there; FR14, design D7 of make-checkpoint-gate-durable).
 *
 * <p>Implements FR1, FR2, FR3, FR5, FR15 of add-git-workflow; FR3, FR5, FR10 of
 * harden-task-branch-contract; FR9 of add-project-registry; FR10 of make-run-headless; FR3, FR7, FR14
 * of make-checkpoint-gate-durable.
 */
public final class GitTaskRepository implements TaskLifecycleStore {

    private static final Logger log = LoggerFactory.getLogger(GitTaskRepository.class);

    private final GitProcessRunner runner;
    private final Path cloneDir;
    private final TaskBranchCreator branchCreator;
    private final TaskWorktreeManager worktreeManager;
    private final ClaimEpochSource epochs;

    /**
     * @param runner the git subprocess runner
     * @param clone the registered clone (the {@code --dir} target) where branch/worktree ops run;
     *     per-task worktrees are materialized in its own worktree folder
     * @param epochs the tenure every lifecycle commit is stamped with (FR13 of
     *     harden-task-branch-contract); {@link ClaimEpochSource#NONE} where no claim is held
     */
    public GitTaskRepository(GitProcessRunner runner, RegisteredClone clone, ClaimEpochSource epochs) {
        this.runner = runner;
        this.cloneDir = clone.clonePath();
        this.branchCreator = new TaskBranchCreator(runner);
        this.worktreeManager = new TaskWorktreeManager(runner, clone);
        this.epochs = epochs;
    }

    @Override
    public void createTask(TaskContext context, ObjectId lawCommit, BasePin pin, TaskState initialState) {
        String taskId = context.taskId();
        BranchCreationResult result = branchCreator.createBranch(cloneDir, taskId, lawCommit);
        String baseCommit =
                switch (result) {
                    case BranchCreationResult.Created created -> created.baseCommit();
                    case BranchCreationResult.AlreadyExists already ->
                        throw new GitTaskRepositoryException(
                                taskId,
                                TaskLifecycleEvent.STARTED,
                                "creating branch",
                                UntrustedText.factory("branch \"" + already.branchName() + "\" already exists"));
                    case BranchCreationResult.BaseCommitMissing missing ->
                        throw new GitTaskRepositoryException(
                                taskId,
                                TaskLifecycleEvent.STARTED,
                                "creating branch",
                                UntrustedText.factory(
                                        "base commit \"" + missing.baseCommit() + "\" is not in this clone"));
                };

        Path worktree = ensureWorktree(taskId);
        TaskJsonDto dto = TaskJsonMapper.toDto(context, baseCommit, Instant.now(), null, null, false, pin);
        StateFileWrite.write(runner, worktree, taskId, initialState, TaskLifecycleEvent.STARTED);
        writeAndCommit(taskId, worktree, dto, TaskLifecycleEvent.STARTED);
    }

    @Override
    public void appendDecision(String taskId, Decision decision, TaskState resetState) {
        Path worktree = ensureWorktree(taskId);
        TaskJsonDto dto = OutcomeClearingTaskJson.withDecision(
                readCommitted(taskId, worktree, TaskLifecycleEvent.RESUMED).dto(), decision);
        // One transition, one commit (FR4): the decision and the attempt-counter reset it implies
        // are staged together, so no tip ever shows one without the other.
        StateFileWrite.write(runner, worktree, taskId, resetState, TaskLifecycleEvent.RESUMED);
        writeAndCommitConsuming(taskId, worktree, dto, TaskLifecycleEvent.RESUMED);
    }

    /**
     * Opens the gate in one worktree commit (FR3 of make-checkpoint-gate-durable): refused on the
     * tip's recorded position before anything is staged ({@link CheckpointApprovalCheck}), then
     * {@code state.json} = {@code approved} and the outcome-cleared {@code task.json} land together.
     */
    @Override
    public void approveCheckpoint(String taskId, Position.AwaitingApproval gate, TaskState approved) {
        TaskLifecycleEvent event = TaskLifecycleEvent.APPROVED;
        Path worktree = ensureWorktree(taskId);
        Position tipPosition =
                RequiredTaskState.atTipOf(runner, worktree, taskId, event).position();
        CheckpointApprovalCheck.requireAdmitted(taskId, gate, tipPosition, approved);
        TaskJsonDto dto = OutcomeClearingTaskJson.of(
                readCommitted(taskId, worktree, event).dto());
        StateFileWrite.write(runner, worktree, taskId, approved, event);
        writeAndCommitConsuming(taskId, worktree, dto, event);
        CheckpointApprovalCheck.approved(taskId, gate, approved);
    }

    /**
     * Consumes the recorded outcome in one worktree commit (FR7, design D4 of
     * make-checkpoint-gate-durable): refused on the tip's {@code task.json} before anything is
     * staged ({@link ResumedWriteCheck}), then {@code state.json} = {@code reset} and the
     * outcome-cleared {@code task.json} land together.
     */
    @Override
    public void resumeFrom(String taskId, TaskState reset) {
        TaskLifecycleEvent event = TaskLifecycleEvent.RESUMED;
        Path worktree = ensureWorktree(taskId);
        TaskJsonDto tip = readCommitted(taskId, worktree, event).dto();
        RecordedOutcome consumed = ResumedWriteCheck.requireRecordedOutcome(taskId, TaskJsonMapper.fromDto(tip));
        StateFileWrite.write(runner, worktree, taskId, reset, event);
        writeAndCommitConsuming(taskId, worktree, OutcomeClearingTaskJson.of(tip), event);
        ResumedWriteCheck.resumed(taskId, consumed, reset);
    }

    @Override
    public void recordOutcome(String taskId, TaskOutcome outcome, TrackerWrite trackerWrite) {
        TaskLifecycleEvent event = TaskOutcomeLifecycleEvent.of(outcome);
        Path worktree = ensureWorktree(taskId);
        CommittedTaskJson committed = readCommitted(taskId, worktree, event);
        TaskJsonDto currentDto = committed.dto();
        TaskRecord current = TaskJsonMapper.fromDto(currentDto);
        var lastEscalation =
                outcome instanceof TaskOutcome.Escalated escalated ? escalated.report() : current.lastEscalation();

        // Durable "terminal write pending" marker (FR10, D10 of add-claim-heartbeat; FR10 of
        // harden-task-branch-contract): a terminal outcome whose external effect is still owed sets
        // it — a PARK (Escalated/Paused) until its tracker park confirms, a Completed until its
        // tracker finish confirms and the cleanup commit removes the whole envelope. This commit is
        // the durable intent, recorded before the tracker write, never after it. A record that owes
        // no tracker write (a manual run's park, design D8 of make-run-headless) sets no marker, and
        // Aborted's tracker write is best-effort and carries none either way.
        boolean pending = trackerWrite == TrackerWrite.OWED && !(outcome instanceof TaskOutcome.Aborted);
        TaskJsonDto dto = TaskJsonMapper.toDto(
                        current.context(),
                        current.baseCommit(),
                        current.createdAt(),
                        outcome,
                        lastEscalation,
                        pending,
                        current.pin())
                .withEgressCursor(currentDto.egressCursor());
        String json = serialize(taskId, dto, event);
        // Idempotence (design D8 of make-run-headless; crash-consistency item 8): a park re-recorded
        // identically leaves the tip as it is — the record is already there, and a commit of it would
        // only fail on "nothing to commit".
        if (committed.carries(json)) {
            log.debug("outcome record for task {} is a no-op: the tip already carries it, event={}", taskId, event);
            return;
        }
        writeAndCommit(taskId, worktree, json, event);
    }

    /**
     * Commits the {@code Completed} cleanup commit — the destructive last step of the completion
     * sequence, run only behind the constructive receipts (FR10 of harden-task-branch-contract).
     * Removing {@code .gnomish-task/} takes the pending marker with it, so the cleaned tip needs no
     * separate receipt, and an already-cleaned tip is left alone ({@link CleanupCommit}).
     *
     * @param taskId the completed task whose envelope is removed from the branch tip; never blank
     */
    @Override
    public void finishCleanup(String taskId) {
        CleanupCommit.commit(
                runner, ensureWorktree(taskId), taskId, epochs.epochFor(taskId).orElse(null));
    }

    /**
     * Clears the durable "tracker-write pending" marker for {@code taskId} once its terminal park's
     * tracker write has confirmed landed, committing the cleared {@code task.json} so a later
     * reconcile-on-resume reads the park as settled rather than orphaned (FR10, D10 of
     * add-claim-heartbeat). {@link TerminalWriteMarker} owns the envelope edit — it names the kill
     * windows and the recovery owner; this class owns worktree resolution and the confirming
     * commit, which it labels {@link TaskLifecycleEvent#RESUMED}. That label records nothing — the
     * message is the fixed {@link ServiceCommitMessages#trackerWriteConfirmed()} and no reader
     * parses it — and {@link TerminalWriteMarker} carries the full reasoning for why the confirm
     * commit owns no event constant of its own, matching what its bare-object twin does.
     *
     * @param taskId the task whose pending marker is cleared; never blank
     */
    @Override
    public void confirmTerminalWrite(String taskId) {
        Path worktree = ensureWorktree(taskId);
        TerminalWriteMarker.clearPending(runner, worktree, taskId);
        commitWith(taskId, worktree, ServiceCommitMessages.trackerWriteConfirmed(), TaskLifecycleEvent.RESUMED);
    }

    private Path ensureWorktree(String taskId) {
        String branchName = TaskIdSanitizer.branchName(taskId);
        return worktreeManager.ensureWorktree(taskId, branchName);
    }

    /**
     * The branch's current {@code task.json} as its raw wire DTO — read this way rather than as a
     * domain record so a rewrite can carry forward the fields the domain does not model, the denial
     * cursor among them (design D8 of fix-denial-attribution-durability). Host mode has no egress
     * guard and so never writes a cursor of its own, but a branch that ran in container mode before
     * this resume carries one, and a host-side lifecycle rewrite must not be what erases it.
     *
     * <p>The read goes through {@link RequiredTaskJson}, which owns it for both host-side
     * rewrites: it resolves at the worktree's {@code HEAD} rather than its working copy, and
     * reports git's own reason for a failed read instead of calling every non-zero exit an
     * absence. Absence at the tip fails exactly as the worktree read's I/O failure did — every
     * transition that rewrites the envelope runs on a branch that carries one.
     */
    private CommittedTaskJson readCommitted(String taskId, Path worktree, TaskLifecycleEvent event) {
        return RequiredTaskJson.atTipOf(runner, worktree, taskId, event);
    }

    private static String serialize(String taskId, TaskJsonDto dto, TaskLifecycleEvent event) {
        try {
            return TaskStateJson.mapper().writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new GitTaskRepositoryException(taskId, event, "serializing task.json", e);
        }
    }

    private void writeAndCommit(String taskId, Path worktree, TaskJsonDto dto, TaskLifecycleEvent event) {
        writeAndCommit(taskId, worktree, serialize(taskId, dto, event), event);
    }

    private void writeAndCommit(String taskId, Path worktree, String json, TaskLifecycleEvent event) {
        writeTaskJson(taskId, worktree, json, event);
        commitWith(taskId, worktree, ServiceCommitMessages.taskEvent(event), event);
    }

    /**
     * The commit of an outcome-clearing write ({@code appendDecision}, {@code approveCheckpoint},
     * {@code resumeFrom}): the envelope is staged, then the consumed requests' removal is staged on
     * top of it ({@link ConsumedRequestRemoval}, FR14 of make-checkpoint-gate-durable), and one
     * commit lands both — the removal after {@code add -A}, so nothing under {@code decisions/} the
     * worktree held is re-added by the staging.
     */
    private void writeAndCommitConsuming(String taskId, Path worktree, TaskJsonDto dto, TaskLifecycleEvent event) {
        writeTaskJson(taskId, worktree, serialize(taskId, dto, event), event);
        stageAll(taskId, worktree, event);
        ConsumedRequestRemoval.stage(runner, worktree, taskId, event);
        commitStaged(taskId, worktree, ServiceCommitMessages.taskEvent(event), event);
    }

    private static void writeTaskJson(String taskId, Path worktree, String json, TaskLifecycleEvent event) {
        try {
            AtomicFileWriter.write(worktree.resolve(EnvelopePaths.TASK_JSON_PATH), json);
        } catch (IOException e) {
            throw new GitTaskRepositoryException(taskId, event, "writing task.json", e);
        }
    }

    /** Stages the worktree whole and commits it — every transition but the outcome-clearing three. */
    private void commitWith(String taskId, Path worktree, String message, TaskLifecycleEvent event) {
        stageAll(taskId, worktree, event);
        commitStaged(taskId, worktree, message, event);
    }

    private void stageAll(String taskId, Path worktree, TaskLifecycleEvent event) {
        GitCommandResult add = runner.run(worktree, "add", "-A");
        if (add.exitCode() != 0) {
            throw new GitTaskRepositoryException(taskId, event, "git add -A", add.stderr());
        }
    }

    /**
     * The host medium's task-lifecycle commit choke point for the transitions this class commits
     * itself — start, the outcome-clearing writes, outcome, terminal-write receipt — which is why the
     * FR2 anchor of harden-logging-observability sits here and not at each of those callers. The {@code
     * Completed} cleanup stages with {@code git rm -r} rather than {@code git add -A}, so it
     * commits and emits the same anchor inside {@link CleanupCommit} instead.
     *
     * <p>The line goes out after the commit succeeds: an anchor states that the transition is on
     * the branch, and a failed commit has not put it there (its own failure travels as the thrown
     * {@link GitTaskRepositoryException}).
     *
     * <p>Kept in sync with {@link TaskLifecycleCommitWriter#build}: both media log one INFO line
     * per lifecycle transition, after the commit succeeds, naming the task and the event.
     */
    private void commitStaged(String taskId, Path worktree, String message, TaskLifecycleEvent event) {
        GitCommandResult commit = runner.run(
                worktree,
                "commit",
                "-m",
                ClaimEpochTrailer.stamp(message, epochs.epochFor(taskId).orElse(null)));
        if (commit.exitCode() != 0) {
            throw new GitTaskRepositoryException(taskId, event, "git commit", commit.stderr());
        }
        log.info("task lifecycle commit written for task {}: event={}", taskId, event);
    }
}
