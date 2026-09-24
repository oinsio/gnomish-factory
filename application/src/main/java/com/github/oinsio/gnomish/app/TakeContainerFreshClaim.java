package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.take.AbortFuse;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TrackerTaskSynthesizer;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.Segment;
import java.nio.file.Path;
import java.util.List;

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
 * resolve+refresh the base, bind the task tier at that base, synthesize, create the branch FROM
 * THE BOUND LAW COMMIT with the base pin beside it (FR15, D12 revised 2026-09-10), run the engine
 * once — over their own execution medium (host worktree vs. sandbox task repository). Both hand
 * on, past the task-tier bind, only the order re-bound to the task's law through {@link
 * TakeOrder#withDefinition} (D6 of introduce-take-order).
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
            TakeOrder order,
            ClaimLossFlag claimLossFlag,
            TrustedBaseContext trustedBase) {
        Path cloneDir = order.run().cloneDir();

        git.branches().harden(cloneDir);

        TaskState notYetStarted = TaskState.atStageStart(
                order.run().definition().stages().getFirst().name());
        var baseRequest = new FreshClaimBaseBinding.Request(order.run().base(), order.trackerTask(), trustedBase);
        return FreshClaimBaseBinding.resolve(
                git.baseRefs(),
                cloneDir,
                baseRequest,
                notYetStarted,
                order.tracker(),
                baseBound -> claimAt(
                        assembly,
                        containerTakeSupport,
                        segments,
                        abortHandler,
                        abortThreshold,
                        credentialEnvVarsToScrub,
                        order,
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
            TakeOrder order,
            ClaimLossFlag claimLossFlag,
            FreshClaimBaseBinding.Bound baseBound) {
        // FR13, D14 of add-base-ref-resolution: the task runs under the definition read from ITS
        // resolved base's law binding, never under the startup one.
        var law = TaskTierLaw.bind(assembly, baseBound.lawBinding(), order);
        if (law instanceof TaskTierLaw.Parked(TakeResult parked)) {
            return parked;
        }
        var bound = (TaskTierLaw.Bound) law;
        // D6 of introduce-take-order: from here on only the order re-bound to the task's law is
        // used — the one this method was handed still carries the startup definition.
        TakeOrder lawBound = order.withDefinition(bound.definition());
        String taskId = lawBound.taskId();
        PipelineDefinition taskDefinition = lawBound.run().definition();

        var synthesized =
                TrackerTaskSynthesizer.synthesize(lawBound.trackerTask().snapshot(), taskDefinition);
        var support = containerTakeSupport
                .containerSupportFactory()
                .create(
                        lawBound.run().cloneDir(),
                        taskId,
                        segments,
                        containerTakeSupport.sandboxProperties(),
                        containerTakeSupport.factoryProperties(),
                        taskDefinition,
                        credentialEnvVarsToScrub);
        // FR15, D12 of add-base-ref-resolution: the branch starts at the very commit the task's law
        // was peeled at — the refreshed base — and the resolved ref travels beside it as the pin.
        GitFreshTaskSupport.createTask(
                support.taskRepository(),
                taskId,
                synthesized.context(),
                bound.lawCommit(),
                baseBound.pin(),
                synthesized.initialState());

        var execution = new TakeContainerEngineExecution(
                assembly,
                new AbortFuse(abortHandler, abortThreshold),
                credentialEnvVarsToScrub,
                claimLossFlag,
                bound.lawBinding());
        return execution.run(lawBound, support, synthesized.context(), synthesized.initialState(), null);
    }
}
