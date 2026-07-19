package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.adapter.git.state.TaskJsonContent;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonDto;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper;
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson;
import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
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
 * TaskOutcome} — populating {@code lastEscalation} for {@code Escalated} — at completion or
 * parking. Shares the branch with the git {@link GitAttemptPersistence} realization of the
 * engine's {@code AttemptPersistence} port; both write into the same worktree's {@code
 * .gnomish-task/}, split by file (D3): this class owns {@code task.json} exclusively.
 *
 * <p>Worktree materialization is this adapter's internal concern, not exposed by the {@link
 * TaskRepository} interface (whose javadoc calls branch/worktree setup "adapter concerns, out of
 * scope for this port"): every method locates or creates the deterministic worktree via {@link
 * TaskWorktreeManager#ensureWorktree}, idempotent whether the worktree already exists (resume) or
 * not (fresh start).
 *
 * <p>Strict port: any failure to durably record a lifecycle event is surfaced as a thrown {@link
 * GitTaskRepositoryException}, never swallowed.
 *
 * <p>On {@code Completed}, {@link #recordOutcome} adds a second, follow-up cleanup commit that
 * removes {@code .gnomish-task/} from the branch tip entirely (FR15, design D4); every prior
 * commit — including the just-written {@code Completed} {@code task.json} — remains reachable in
 * branch history as the audit trail (M4).
 *
 * <p>Implements FR1, FR2, FR3, FR5, FR15 of add-git-workflow.
 */
public final class GitTaskRepository implements TaskRepository {

    private final GitProcessRunner runner;
    private final Path cloneDir;
    private final TaskBranchCreator branchCreator;
    private final TaskWorktreeManager worktreeManager;

    /**
     * @param runner the git subprocess runner
     * @param cloneDir the working directory of the existing git clone (the {@code --dir} target);
     *     branch creation and {@code git worktree add} run here
     * @param worktreesRoot the root directory under which per-task worktrees are materialized, see
     *     {@link TaskWorktreeManager}
     */
    public GitTaskRepository(GitProcessRunner runner, Path cloneDir, Path worktreesRoot) {
        this.runner = runner;
        this.cloneDir = cloneDir;
        this.branchCreator = new TaskBranchCreator(runner);
        this.worktreeManager = new TaskWorktreeManager(runner, worktreesRoot);
    }

    @Override
    public void createTask(TaskContext context, String baseRef) {
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
        TaskJsonDto dto = TaskJsonMapper.toDto(context, baseCommit, Instant.now(), null, null);
        writeAndCommit(taskId, worktree, dto, TaskLifecycleEvent.STARTED);
    }

    @Override
    public void appendDecision(String taskId, Decision decision) {
        Path worktree = ensureWorktree(taskId);
        TaskJsonContent current = readCurrent(taskId, worktree, TaskLifecycleEvent.RESUMED);

        List<Decision> decisions = new ArrayList<>(current.context().decisions());
        decisions.add(decision);
        TaskContext updatedContext = new TaskContext(
                current.context().taskId(),
                current.context().title(),
                current.context().body(),
                decisions);

        TaskJsonDto dto = TaskJsonMapper.toDto(
                updatedContext, current.baseCommit(), current.createdAt(), null, current.lastEscalation());
        writeAndCommit(taskId, worktree, dto, TaskLifecycleEvent.RESUMED);
    }

    @Override
    public void recordOutcome(String taskId, TaskOutcome outcome) {
        TaskLifecycleEvent event = eventFor(outcome);
        Path worktree = ensureWorktree(taskId);
        TaskJsonContent current = readCurrent(taskId, worktree, event);
        var lastEscalation =
                outcome instanceof TaskOutcome.Escalated escalated ? escalated.report() : current.lastEscalation();

        TaskJsonDto dto = TaskJsonMapper.toDto(
                current.context(), current.baseCommit(), current.createdAt(), outcome, lastEscalation);
        writeAndCommit(taskId, worktree, dto, event);

        if (outcome instanceof TaskOutcome.Completed) {
            commitCleanup(taskId, worktree);
        }
    }

    /**
     * Follow-up commit for {@code Completed} (FR15/M4): removes {@code .gnomish-task/} from both
     * the worktree and the index in one step via {@code git rm -r}, then commits with the fixed
     * cleanup message. History is untouched — only this new commit stops tracking the directory,
     * so every prior round/lifecycle commit (including the just-written {@code Completed}
     * task.json) remains reachable via {@code git show <sha>:.gnomish-task/...}.
     */
    private void commitCleanup(String taskId, Path worktree) {
        GitCommandResult rm = runner.run(worktree, "rm", "-r", ".gnomish-task");
        if (rm.exitCode() != 0) {
            throw new GitTaskRepositoryException(
                    taskId, TaskLifecycleEvent.COMPLETED, "git rm -r .gnomish-task", rm.stderr());
        }

        GitCommandResult commit = runner.run(worktree, "commit", "-m", ServiceCommitMessages.cleanup());
        if (commit.exitCode() != 0) {
            throw new GitTaskRepositoryException(
                    taskId, TaskLifecycleEvent.COMPLETED, "git commit (cleanup)", commit.stderr());
        }
    }

    private Path ensureWorktree(String taskId) {
        String branchName = TaskIdSanitizer.branchName(taskId);
        return worktreeManager.ensureWorktree(cloneDir, taskId, branchName);
    }

    private TaskJsonContent readCurrent(String taskId, Path worktree, TaskLifecycleEvent event) {
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
            Files.createDirectories(gnomishTaskRoot);
            String json = TaskStateJson.mapper().writeValueAsString(dto);
            Files.writeString(gnomishTaskRoot.resolve("task.json"), json);
        } catch (IOException e) {
            throw new GitTaskRepositoryException(taskId, event, "writing task.json", e);
        }

        GitCommandResult add = runner.run(worktree, "add", "-A");
        if (add.exitCode() != 0) {
            throw new GitTaskRepositoryException(taskId, event, "git add -A", add.stderr());
        }

        GitCommandResult commit = runner.run(worktree, "commit", "-m", ServiceCommitMessages.taskEvent(event));
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
