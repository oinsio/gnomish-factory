package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TrackerTaskSynthesizer;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.Segment;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The container-mode counterpart of {@link TakeFreshClaim} (FR1 of add-serve-sandbox-lifecycle):
 * creates the task branch factory-side over bare git objects instead of a host worktree —
 * mirroring {@link ContainerGitModeRunner}'s fresh-run sequence — with {@code tracked}
 * labelling (as opposed to {@code run}'s {@code manual} labelling, per the {@link
 * ContainerTakeSupport#containerSupportFactory()} the caller resolved), then runs the engine once
 * through {@link TakeContainerEngineExecution}.
 *
 * <p>Implements FR1, FR2 of add-serve-sandbox-lifecycle; FR9, FR11, D3 of add-tracker-port.
 */
final class TakeContainerFreshClaim {

    private TakeContainerFreshClaim() {}

    static TakeResult claim(
            RunAssembly assembly,
            TaskGit git,
            ContainerTakeSupport containerTakeSupport,
            List<Segment> segments,
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
            ClaimLossFlag claimLossFlag) {
        String taskId = trackerTask.snapshot().id();

        git.branches().harden(cloneDir);

        var synthesized = TrackerTaskSynthesizer.synthesize(trackerTask.snapshot(), definition);
        var support = containerTakeSupport
                .containerSupportFactory()
                .create(
                        cloneDir,
                        taskId,
                        segments,
                        containerTakeSupport.sandboxProperties(),
                        containerTakeSupport.factoryProperties(),
                        definition,
                        credentialEnvVarsToScrub);
        GitFreshTaskSupport.createTask(
                support.taskRepository(), taskId, synthesized.context(), base, synthesized.initialState());

        var execution = new TakeContainerEngineExecution(
                assembly, abortHandler, abortThreshold, credentialEnvVarsToScrub, claimLossFlag, cloneDir);
        return execution.run(
                support,
                definition,
                synthesized.context(),
                synthesized.initialState(),
                interactiveMode,
                tracker,
                trackerTask.ref(),
                instanceId,
                taskId,
                null);
    }
}
