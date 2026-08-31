package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.atomicfile.AtomicFileWriter;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The git realization of {@link TaskRepository} (design D1): creates the task branch and worktree
 * and writes the first {@code task.json} commit at start, appends resume {@link Decision}s
 * (resetting {@code outcome} to null in the same commit, FR5/D9), and records the terminal {@link
 * TaskOutcome} — populating {@code lastEscalation} for {@code Escalated} — at completion or parking.
 * Shares the branch with {@link GitAttemptPersistence}, split by file (D3): this class owns {@code
 * task.json} exclusively. Worktree setup is an internal concern (not on the port): every method
 * resolves the deterministic worktree via {@link TaskWorktreeManager#ensureWorktree}, idempotent
 * for both fresh start and resume.
 *
 * <p>Strict port: any failure to durably record a lifecycle event is thrown as {@link
 * GitTaskRepositoryException}. On {@code Completed} the cleanup commit removing {@code .gnomish-task/}
 * from the branch tip is {@link #finishCleanup}, driven separately as the destructive last step of
 * the completion sequence (FR15, design D4; FR10 of harden-task-branch-contract); prior commits stay
 * reachable as the audit trail (M4).
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
 * <p>Implements FR1, FR2, FR3, FR5, FR15 of add-git-workflow; FR3, FR5, FR10 of
 * harden-task-branch-contract.
 */
public final class GitTaskRepository implements TaskLifecycleStore {

    private final GitProcessRunner runner;
    private final Path cloneDir;
    private final TaskBranchCreator branchCreator;
    private final TaskWorktreeManager worktreeManager;
    private final ClaimEpochSource epochs;

    /**
     * @param runner the git subprocess runner
     * @param cloneDir the existing git clone (the {@code --dir} target) where branch/worktree ops run
     * @param worktreesRoot the root under which per-task worktrees are materialized
     * @param epochs the tenure every lifecycle commit is stamped with (FR13 of
     *     harden-task-branch-contract); {@link ClaimEpochSource#NONE} where no claim is held
     */
    public GitTaskRepository(GitProcessRunner runner, Path cloneDir, Path worktreesRoot, ClaimEpochSource epochs) {
        this.runner = runner;
        this.cloneDir = cloneDir;
        this.branchCreator = new TaskBranchCreator(runner);
        this.worktreeManager = new TaskWorktreeManager(runner, worktreesRoot);
        this.epochs = epochs;
    }

    @Override
    public void createTask(TaskContext context, String baseRef, TaskState initialState) {
        String taskId = context.taskId();
        BranchCreationResult result = branchCreator.createBranch(cloneDir, taskId, baseRef);
        String baseCommit =
                switch (result) {
                    case BranchCreationResult.Created created -> created.baseCommit();
                    case BranchCreationResult.AlreadyExists already ->
                        throw new GitTaskRepositoryException(
                                taskId,
                                TaskLifecycleEvent.STARTED,
                                "creating branch",
                                "branch \"" + already.branchName() + "\" already exists");
                    case BranchCreationResult.BaseRefNotResolved notResolved ->
                        throw new GitTaskRepositoryException(
                                taskId,
                                TaskLifecycleEvent.STARTED,
                                "creating branch",
                                "base ref \"" + notResolved.baseRef() + "\" did not resolve");
                };

        Path worktree = ensureWorktree(taskId);
        TaskJsonDto dto = TaskJsonMapper.toDto(context, baseCommit, Instant.now(), null, null, false);
        StateFileWrite.write(worktree, taskId, initialState, TaskLifecycleEvent.STARTED);
        writeAndCommit(taskId, worktree, dto, TaskLifecycleEvent.STARTED);
    }

    @Override
    public void appendDecision(String taskId, Decision decision, TaskState resetState) {
        Path worktree = ensureWorktree(taskId);
        TaskRecord current = readCurrent(taskId, worktree, TaskLifecycleEvent.RESUMED);

        List<Decision> decisions = new ArrayList<>(current.context().decisions());
        decisions.add(decision);
        TaskContext updatedContext = new TaskContext(
                current.context().taskId(),
                current.context().title(),
                current.context().body(),
                decisions);

        TaskJsonDto dto = TaskJsonMapper.toDto(
                updatedContext, current.baseCommit(), current.createdAt(), null, current.lastEscalation(), false);
        // One transition, one commit (FR4): the decision and the attempt-counter reset it implies
        // are staged together, so no tip ever shows one without the other.
        StateFileWrite.write(worktree, taskId, resetState, TaskLifecycleEvent.RESUMED);
        writeAndCommit(taskId, worktree, dto, TaskLifecycleEvent.RESUMED);
    }

    @Override
    public void recordOutcome(String taskId, TaskOutcome outcome) {
        TaskLifecycleEvent event = eventFor(outcome);
        Path worktree = ensureWorktree(taskId);
        TaskRecord current = readCurrent(taskId, worktree, event);
        var lastEscalation =
                outcome instanceof TaskOutcome.Escalated escalated ? escalated.report() : current.lastEscalation();

        // Durable "terminal write pending" marker (FR10, D10 of add-claim-heartbeat; FR10 of
        // harden-task-branch-contract): every terminal outcome whose external effect is still owed
        // sets it — a PARK (Escalated/Paused) until its tracker park confirms, a Completed until its
        // tracker finish confirms and the cleanup commit removes the whole envelope. This commit is
        // the durable intent, recorded before the tracker write, never after it. Aborted's tracker
        // write is best-effort and carries no marker.
        boolean pending = !(outcome instanceof TaskOutcome.Aborted);
        TaskJsonDto dto = TaskJsonMapper.toDto(
                current.context(), current.baseCommit(), current.createdAt(), outcome, lastEscalation, pending);
        writeAndCommit(taskId, worktree, dto, event);
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
     * add-claim-heartbeat). The marker rewrite is delegated to {@link TerminalWriteMarker} (file
     * size); this class owns worktree resolution and the confirming commit.
     *
     * @param taskId the task whose pending marker is cleared; never blank
     */
    @Override
    public void confirmTerminalWrite(String taskId) {
        Path worktree = ensureWorktree(taskId);
        TerminalWriteMarker.clearPending(worktree, taskId);
        commitWith(taskId, worktree, ServiceCommitMessages.trackerWriteConfirmed(), TaskLifecycleEvent.RESUMED);
    }

    private Path ensureWorktree(String taskId) {
        String branchName = TaskIdSanitizer.branchName(taskId);
        return worktreeManager.ensureWorktree(cloneDir, taskId, branchName);
    }

    private TaskRecord readCurrent(String taskId, Path worktree, TaskLifecycleEvent event) {
        Path taskJson = worktree.resolve(".gnomish-task").resolve("task.json");
        String json;
        try {
            json = Files.readString(taskJson);
        } catch (IOException e) {
            throw new GitTaskRepositoryException(taskId, event, "reading task.json", e);
        }
        return TaskJsonMapper.fromDto(TaskJsonMapper.readDto(json));
    }

    private void writeAndCommit(String taskId, Path worktree, TaskJsonDto dto, TaskLifecycleEvent event) {
        Path gnomishTaskRoot = worktree.resolve(".gnomish-task");
        try {
            String json = TaskStateJson.mapper().writeValueAsString(dto);
            AtomicFileWriter.write(gnomishTaskRoot.resolve("task.json"), json);
        } catch (IOException e) {
            throw new GitTaskRepositoryException(taskId, event, "writing task.json", e);
        }

        commitWith(taskId, worktree, ServiceCommitMessages.taskEvent(event), event);
    }

    private void commitWith(String taskId, Path worktree, String message, TaskLifecycleEvent event) {
        GitCommandResult add = runner.run(worktree, "add", "-A");
        if (add.exitCode() != 0) {
            throw new GitTaskRepositoryException(taskId, event, "git add -A", add.stderr());
        }
        GitCommandResult commit = runner.run(
                worktree,
                "commit",
                "-m",
                ClaimEpochTrailer.stamp(message, epochs.epochFor(taskId).orElse(null)));
        if (commit.exitCode() != 0) {
            throw new GitTaskRepositoryException(taskId, event, "git commit", commit.stderr());
        }
    }

    private static TaskLifecycleEvent eventFor(TaskOutcome outcome) {
        return switch (outcome) {
            case TaskOutcome.Completed ignored -> TaskLifecycleEvent.COMPLETED;
            case TaskOutcome.Paused ignored -> TaskLifecycleEvent.PAUSED;
            case TaskOutcome.Escalated ignored -> TaskLifecycleEvent.ESCALATED;
            case TaskOutcome.Aborted ignored -> TaskLifecycleEvent.ABORTED;
        };
    }
}
