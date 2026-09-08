package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.take.AbortFuse;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TrackerTaskSynthesizer;
import com.github.oinsio.gnomish.domain.engine.TaskState;
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
 * <p>Like its host twin, the base the task branches from is resolved and freshly refreshed via
 * {@link FreshClaimBaseBinding} (FR2, FR6, D6, D15 of add-base-ref-resolution) before anything
 * durable is created, and the task's definition is then read from that resolved commit's law
 * binding ({@link TaskTierLaw}, FR13), never from the startup definition the caller holds.
 *
 * <p>Kept in sync with {@link TakeFreshClaim}: both run the SAME fresh-claim recipe — harden,
 * resolve+refresh the base, bind the task tier at that base, synthesize, create the branch, run
 * the engine once — over their own execution medium (host worktree vs. sandbox task repository).
 *
 * <p>Implements FR1, FR2 of add-serve-sandbox-lifecycle; FR9, FR11, D3 of add-tracker-port; FR2,
 * FR6, FR13, D6, D15 of add-base-ref-resolution.
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
            ClaimLossFlag claimLossFlag,
            TrustedBaseContext trustedBase) {
        String taskId = trackerTask.snapshot().id();

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
                        containerTakeSupport,
                        segments,
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
            ContainerTakeSupport containerTakeSupport,
            List<Segment> segments,
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
        var support = containerTakeSupport
                .containerSupportFactory()
                .create(
                        cloneDir,
                        taskId,
                        segments,
                        containerTakeSupport.sandboxProperties(),
                        containerTakeSupport.factoryProperties(),
                        taskDefinition,
                        credentialEnvVarsToScrub);
        // FR4, FR10 of add-base-ref-resolution: the resolved ref, not the raw --base argument, is
        // what the branch is created from.
        GitFreshTaskSupport.createTask(
                support.taskRepository(),
                taskId,
                synthesized.context(),
                baseBound.decision(),
                synthesized.initialState());

        var execution = new TakeContainerEngineExecution(
                assembly,
                new AbortFuse(abortHandler, abortThreshold),
                credentialEnvVarsToScrub,
                claimLossFlag,
                bound.lawBinding());
        return execution.run(
                support,
                taskDefinition,
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
