package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.slf4j.MDC;

/**
 * The explicit-mode and bare-auto dispatch of one {@code gnomish take} invocation, extracted from
 * {@link TakeCommand} for file size. Holds the per-invocation-invariant collaborators; each dispatch
 * method takes the run-specific values ({@link Tracker}, {@link TakeHeartbeat}, assembly) built by
 * {@link TakeCommand#run}.
 *
 * <p>Implements FR9, FR10, D8, D15, D16 of add-tracker-port.
 */
record TakeDispatcher(
        TaskGit git,
        Path worktreesRoot,
        String taskIdMdcKey,
        FactoryProperties factoryProperties,
        Clock clock,
        Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
        SecretsProvider secretsProvider,
        TakeoverConfirmation takeoverConfirmation,
        ContainerTakeSupport containerTakeSupport) {

    TakeResult runExplicit(
            TakeArguments takeArguments,
            String rawRef,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            List<String> credentialEnvVarsToScrub,
            TrackerAdapterFactory factory,
            RunAssembly takeAssembly,
            TakeHeartbeat heartbeat) {
        return runOneRef(
                takeArguments,
                rawRef,
                definition,
                trackerConfig,
                tracker,
                instanceId,
                credentialEnvVarsToScrub,
                factory,
                takeAssembly,
                heartbeat,
                takeoverConfirmation);
    }

    /**
     * The per-ref disposition body shared by {@link #runExplicit} (this invocation's own {@link
     * #takeoverConfirmation}) and batch (always {@link TakeoverConfirmation#UNAVAILABLE} — FR4).
     *
     * <p>Implements FR9 of add-tracker-port; FR3, FR4 of add-factory-serve.
     */
    TakeResult runOneRef(
            TakeArguments takeArguments,
            String rawRef,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            List<String> credentialEnvVarsToScrub,
            TrackerAdapterFactory factory,
            RunAssembly takeAssembly,
            TakeHeartbeat heartbeat,
            TakeoverConfirmation confirmation) {
        // NFR-O1: the canonical ref is known as soon as short-ref expansion resolves it, before
        // fetchTask/dispose ever run — so every explicit-mode disposition outcome, including a
        // refusal (AwaitingHuman/Working/Finished/Gone in TakeDisposition, none of which reach any
        // deeper resume/fresh-claim MDC-setting code), is logged under the correct taskId.
        TaskRef ref = TakeRefResolution.resolve(rawRef, trackerConfig, trackerAdapterRegistry);
        MDC.put(taskIdMdcKey, ref.id());
        // FR9, design D8: a full canonical id naming a repo the adapter cannot reconcile to the
        // configured binding (GitHub: neither the configured repo nor a rename predecessor of it)
        // is refused here — before fetchTask ever touches the foreign repo — as exit 15 (Skipped),
        // never silently acted on. Adapters whose refs carry no repo binding return empty.
        Optional<String> foreignRefusal = factory.refuseForeignRef(secretsProvider, trackerConfig, ref);
        if (foreignRefusal.isPresent()) {
            return new TakeResult.Skipped(foreignRefusal.get());
        }
        TrackerTask trackerTask = tracker.fetchTask(ref);
        var disposition = new TakeDisposition(
                takeAssembly,
                git,
                worktreesRoot,
                newAbortHandler(tracker),
                trackerConfig.abortThreshold(),
                taskIdMdcKey,
                credentialEnvVarsToScrub,
                heartbeat.instance(),
                takeArguments.takeover(),
                confirmation,
                clock,
                heartbeat.flag(),
                containerTakeSupport);
        return disposition.dispose(
                takeArguments.dir(),
                takeArguments.base(),
                definition,
                takeArguments.interactiveMode(),
                takeArguments.discardWork(),
                trackerTask,
                tracker,
                instanceId);
    }

    TakeResult runBare(
            TakeArguments takeArguments,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            List<String> credentialEnvVarsToScrub,
            RunAssembly takeAssembly,
            TakeHeartbeat heartbeat) {
        FactoryProperties.Tracker trackerProperties = factoryProperties.tracker();
        var bareAuto = new TakeBareAuto(
                takeAssembly,
                git,
                worktreesRoot,
                newAbortHandler(tracker),
                trackerConfig.abortThreshold(),
                taskIdMdcKey,
                trackerProperties.abortBackoffBase(),
                trackerProperties.abortBackoffCap(),
                clock,
                credentialEnvVarsToScrub,
                heartbeat.instance(),
                heartbeat.flag(),
                trackerConfig.wipLimit(),
                new Random(),
                containerTakeSupport);
        return bareAuto.run(takeArguments.dir(), definition, takeArguments.interactiveMode(), tracker, instanceId);
    }

    /**
     * Batch mode ({@code take <ref> <ref> ...}, two or more refs), validated by {@link
     * TakeArgumentsParser}: delegates to {@link TakeBatch#dispatch} for the scheduler-driven
     * per-ref disposition matrix (see its Javadoc for the full contract).
     *
     * <p>Implements FR3, FR4, D6 of add-factory-serve.
     *
     * @param slots the concurrency limit N (design D3's {@code factory.serve.slots}); positive
     * @throws InterruptedException if interrupted while waiting for a free slot or an in-flight ref
     */
    List<TakeBatchOutcome> runBatch(
            TakeArguments takeArguments,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            List<String> credentialEnvVarsToScrub,
            TrackerAdapterFactory factory,
            RunAssembly takeAssembly,
            TakeHeartbeat heartbeat,
            int slots)
            throws InterruptedException {
        return TakeBatch.dispatch(
                this,
                taskIdMdcKey,
                takeArguments,
                definition,
                trackerConfig,
                tracker,
                instanceId,
                credentialEnvVarsToScrub,
                factory,
                takeAssembly,
                heartbeat,
                slots);
    }

    private AbortHandler newAbortHandler(Tracker tracker) {
        return new AbortHandler(tracker, clock);
    }
}
