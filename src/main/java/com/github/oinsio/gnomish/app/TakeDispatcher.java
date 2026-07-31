package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
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
import org.slf4j.MDC;

/**
 * The explicit-mode and bare-auto dispatch of one {@code gnomish take} invocation, extracted from
 * {@link TakeCommand} for file size. Holds the per-invocation-invariant collaborators; each dispatch
 * method takes the run-specific values ({@link Tracker}, {@link TakeHeartbeat}, assembly) built by
 * {@link TakeCommand#run}.
 *
 * <p>Implements FR9, FR10, D8, D15, D16 of add-tracker-port.
 */
final class TakeDispatcher {

    private final Path worktreesRoot;
    private final String taskIdMdcKey;
    private final FactoryProperties factoryProperties;
    private final Clock clock;
    private final Map<String, TrackerAdapterFactory> trackerAdapterRegistry;
    private final TakeoverConfirmation takeoverConfirmation;

    TakeDispatcher(
            Path worktreesRoot,
            String taskIdMdcKey,
            FactoryProperties factoryProperties,
            Clock clock,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
            TakeoverConfirmation takeoverConfirmation) {
        this.worktreesRoot = worktreesRoot;
        this.taskIdMdcKey = taskIdMdcKey;
        this.factoryProperties = factoryProperties;
        this.clock = clock;
        this.trackerAdapterRegistry = trackerAdapterRegistry;
        this.takeoverConfirmation = takeoverConfirmation;
    }

    TakeResult runExplicit(
            TakeArguments takeArguments,
            String rawRef,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            List<String> credentialEnvVarsToScrub,
            TrackerAdapterFactory factory,
            ManualRunAssembly takeAssembly,
            TakeHeartbeat heartbeat) {
        // NFR-O1: the canonical ref is known as soon as short-ref expansion resolves it, before
        // fetchTask/dispose ever run — so every explicit-mode disposition outcome, including a
        // refusal (AwaitingHuman/Working/Finished/Gone in TakeDisposition, none of which reach any
        // deeper resume/fresh-claim MDC-setting code), is logged under the correct taskId.
        TaskRef ref = resolveExplicitRef(rawRef, trackerConfig);
        MDC.put(taskIdMdcKey, ref.id());
        // FR9, design D8: a full canonical id naming a repo the adapter cannot reconcile to the
        // configured binding (GitHub: neither the configured repo nor a rename predecessor of it)
        // is refused here — before fetchTask ever touches the foreign repo — as exit 15 (Skipped),
        // never silently acted on. Adapters whose refs carry no repo binding return empty.
        Optional<String> foreignRefusal = factory.refuseForeignRef(trackerConfig, ref);
        if (foreignRefusal.isPresent()) {
            return new TakeResult.Skipped(foreignRefusal.get());
        }
        TrackerTask trackerTask = tracker.fetchTask(ref);
        var disposition = new TakeDisposition(
                takeAssembly,
                worktreesRoot,
                newAbortHandler(tracker),
                trackerConfig.abortThreshold(),
                taskIdMdcKey,
                credentialEnvVarsToScrub,
                heartbeat.instance(),
                takeArguments.takeover(),
                takeoverConfirmation,
                clock,
                heartbeat.flag());
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

    /**
     * Builds the {@link TaskRef} for explicit mode from the raw {@code <ref>} string (FR9): a ref
     * matching the short-ref shape (`42`, `#42`) is expanded via the registered adapter factory for
     * {@code trackerConfig.type()}; anything else (an already-canonical ref) is wrapped as-is.
     *
     * @throws UsageException if {@code ref} looks like a short ref but no adapter factory is
     *     registered for {@code trackerConfig.type()}
     */
    private TaskRef resolveExplicitRef(String ref, TrackerConfig trackerConfig) {
        if (!ShortRef.isShortRef(ref)) {
            return new TaskRef(ref);
        }
        TrackerAdapterFactory factory = trackerAdapterRegistry.get(trackerConfig.type());
        if (factory == null) {
            throw new UsageException("cannot expand short ref '" + ref + "': unknown tracker type '"
                    + trackerConfig.type() + "' — supported: "
                    + TakeCommandSupport.supportedTypes(trackerAdapterRegistry));
        }
        return factory.expandRef(trackerConfig, ref);
    }

    TakeResult runBare(
            TakeArguments takeArguments,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            List<String> credentialEnvVarsToScrub,
            ManualRunAssembly takeAssembly,
            TakeHeartbeat heartbeat) {
        FactoryProperties.Tracker trackerProperties = factoryProperties.tracker();
        var bareAuto = new TakeBareAuto(
                takeAssembly,
                worktreesRoot,
                newAbortHandler(tracker),
                trackerConfig.abortThreshold(),
                taskIdMdcKey,
                trackerProperties.abortBackoffBase(),
                trackerProperties.abortBackoffCap(),
                clock,
                credentialEnvVarsToScrub,
                heartbeat.instance(),
                heartbeat.flag());
        return bareAuto.run(takeArguments.dir(), definition, takeArguments.interactiveMode(), tracker, instanceId);
    }

    private AbortHandler newAbortHandler(Tracker tracker) {
        return new AbortHandler(tracker, clock);
    }
}
