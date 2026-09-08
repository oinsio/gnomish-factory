package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.git.TaskWorktreePath;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.git.TaskRecord;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.take.AbortFuse;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TrackerTaskSynthesizer;
import com.github.oinsio.gnomish.domain.engine.TaskState;
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
 * <p>The base the task branches from is resolved and freshly refreshed via {@link
 * FreshClaimBaseBinding} (FR2, FR6, D6, D15 of add-base-ref-resolution) before anything durable is
 * created; the definition the task runs under is then read from THAT resolved commit's law
 * binding ({@link TaskTierLaw}, FR13), never from the startup definition the caller holds. A base
 * that cannot be resolved or refreshed parks or releases the claim there — see {@link
 * FreshClaimBaseBinding}'s javadoc for the classification.
 *
 * <p>Split out of {@link TakeDisposition} purely to respect the file-size guidance
 * (`.claude/rules/process-invariants.md`).
 *
 * <p>Kept in sync with {@link TakeContainerFreshClaim}: both run the SAME fresh-claim recipe —
 * harden, resolve+refresh the base, bind the task tier at that base, synthesize, create the
 * branch, run the engine once — over their own execution medium (host worktree vs. sandbox task
 * repository).
 *
 * <p>Implements FR9, FR11, D3 of add-tracker-port; FR2, FR6, FR13, D6, D15 of
 * add-base-ref-resolution.
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
            RunAssembly assembly,
            TaskGit git,
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
            InstanceId instanceId,
            ClaimLossFlag claimLossFlag,
            TrustedBaseContext trustedBase) {
        String taskId = trackerTask.snapshot().id();

        git.worktrees().pruneWorktrees(cloneDir);
        git.branches().harden(cloneDir);

        TaskState notYetStarted =
                TaskState.atStageStart(definition.stages().getFirst().name());
        var baseRequest = new FreshClaimBaseBinding.Request(base, trackerTask, trustedBase);
        return FreshClaimBaseBinding.resolve(
                git.baseRefs(),
                cloneDir,
                baseRequest,
                notYetStarted,
                tracker,
                baseBound -> claimAt(
                        assembly,
                        git,
                        worktreesRoot,
                        abortHandler,
                        abortThreshold,
                        credentialEnvVarsToScrub,
                        cloneDir,
                        taskId,
                        definition,
                        interactiveMode,
                        trackerTask,
                        tracker,
                        instanceId,
                        claimLossFlag,
                        baseBound));
    }

    /**
     * The remainder of a fresh claim once its base is resolved and refreshed: bind the task tier
     * at that base, create the branch from the resolved ref, and run the engine once.
     */
    private static TakeResult claimAt(
            RunAssembly assembly,
            TaskGit git,
            Path worktreesRoot,
            AbortHandler abortHandler,
            int abortThreshold,
            List<String> credentialEnvVarsToScrub,
            Path cloneDir,
            String taskId,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            TrackerTask trackerTask,
            Tracker tracker,
            InstanceId instanceId,
            ClaimLossFlag claimLossFlag,
            FreshClaimBaseBinding.Bound baseBound) {
        // FR13, D14 of add-base-ref-resolution: the task runs under the definition read from ITS
        // resolved base's law binding, never under the startup one.
        var law = TaskTierLaw.bind(assembly, baseBound.lawBinding(), definition, trackerTask, tracker);
        if (law instanceof TaskTierLaw.Parked(TakeResult parked)) {
            return parked;
        }
        var bound = (TaskTierLaw.Bound) law;
        PipelineDefinition taskDefinition = bound.definition();

        var synthesized = TrackerTaskSynthesizer.synthesize(trackerTask.snapshot(), taskDefinition);
        var taskRepository = git.store().taskRepository(cloneDir, worktreesRoot);
        // FR4, FR10 of add-base-ref-resolution: the resolved ref, not the raw --base argument, is
        // what the branch is created from.
        GitFreshTaskSupport.createTask(
                taskRepository, taskId, synthesized.context(), baseBound.decision(), synthesized.initialState());

        Path worktree = TaskWorktreePath.resolve(worktreesRoot, cloneDir, taskId);
        TaskRecord content = git.store().readTaskRecord(worktree);
        String branchName = TaskIdSanitizer.branchName(taskId);
        var bootstrap = new ResumeBootstrap(
                taskId,
                content.context(),
                content.outcome(),
                content.lastEscalation(),
                worktree,
                branchName,
                content.baseCommit(),
                content.trackerWritePending(),
                content.baseRef(),
                content.baseRule());

        var execution = new TakeEngineExecution(
                assembly,
                git,
                worktreesRoot,
                new AbortFuse(abortHandler, abortThreshold),
                credentialEnvVarsToScrub,
                claimLossFlag,
                bound.lawBinding());
        return execution.run(
                taskDefinition,
                bootstrap,
                synthesized.context(),
                synthesized.initialState(),
                interactiveMode,
                tracker,
                trackerTask.ref(),
                instanceId);
    }
}
