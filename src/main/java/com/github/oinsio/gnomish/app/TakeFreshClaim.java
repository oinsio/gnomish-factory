package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository;
import com.github.oinsio.gnomish.adapter.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.adapter.git.TaskWorktreeCleanup;
import com.github.oinsio.gnomish.adapter.git.TaskWorktreePath;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonContent;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TrackerTaskSynthesizer;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The "no branch exists yet" half of {@link TakeDisposition}'s {@code Ready} case (FR9, FR11,
 * D3): synthesizes the initial {@link com.github.oinsio.gnomish.domain.engine.TaskContext}/{@link
 * com.github.oinsio.gnomish.domain.engine.TaskState} pair from the already-fetched {@link
 * TrackerTask}'s snapshot (FR11's "snapshot at first claim" — the snapshot was already fetched by
 * the caller as part of {@code fetchTask}; this class never re-fetches it), creates the task
 * branch/worktree via {@link GitFreshTaskSupport#createTask} (mirroring {@link GitModeRunner}'s
 * fresh-run sequence), reads back {@code task.json} to build a {@link ResumeBootstrap}, then runs
 * the engine once through {@link TakeEngineExecution} — the same execution tail resume uses,
 * since it only reads {@code worktreePath}/{@code taskId}/{@code branchName} off the bootstrap and
 * never assumes the branch pre-existed.
 *
 * <p>Split out of {@link TakeDisposition} purely to respect the file-size guidance
 * (`.claude/rules/process-invariants.md`).
 *
 * <p>Implements FR9, FR11, D3 of add-tracker-port.
 */
final class TakeFreshClaim {

    private TakeFreshClaim() {}

    /**
     * Creates the task branch/worktree for a first claim and runs the engine once (see class
     * javadoc).
     *
     * <p>Implements FR9, FR11, D3 of add-tracker-port.
     */
    static TakeResult claim(
            ManualRunAssembly assembly,
            Path worktreesRoot,
            AbortHandler abortHandler,
            int abortThreshold,
            List<String> credentialEnvVarsToScrub,
            Path cloneDir,
            @Nullable String base,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            TrackerTask trackerTask,
            Tracker tracker,
            InstanceId instanceId) {
        GitProcessRunner runner = new GitProcessRunner();
        String taskId = trackerTask.snapshot().id();

        new TaskWorktreeCleanup(runner).pruneWorktrees(cloneDir);

        var synthesized = TrackerTaskSynthesizer.synthesize(trackerTask.snapshot(), definition);
        var taskRepository = new GitTaskRepository(runner, cloneDir, worktreesRoot);
        GitFreshTaskSupport.createTask(taskRepository, taskId, synthesized.context(), base);

        Path worktree = TaskWorktreePath.resolve(worktreesRoot, cloneDir, taskId);
        TaskJsonContent content = GitFreshTaskSupport.readTaskJson(worktree);
        String branchName = TaskIdSanitizer.branchName(taskId);
        var bootstrap = new ResumeBootstrap(
                taskId,
                content.context(),
                content.outcome(),
                content.lastEscalation(),
                worktree,
                branchName,
                content.baseCommit());

        var execution = new TakeEngineExecution(
                assembly, runner, cloneDir, worktreesRoot, abortHandler, abortThreshold, credentialEnvVarsToScrub);
        return execution.run(
                definition,
                bootstrap,
                synthesized.context(),
                synthesized.initialState(),
                interactiveMode,
                tracker,
                trackerTask.ref(),
                instanceId);
    }
}
