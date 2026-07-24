package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.TakeExitCodeMapper;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;

/**
 * {@code gnomish take [<ref>]} (FR9, FR10, FR17 of add-tracker-port; design D4, D15, D16): the
 * single-task tracker CLI, wired beside {@code run}/{@code status}/{@code usage} but with its own,
 * entirely separate flag set (parsed by {@link TakeArgumentsParser}). Given a {@code <ref>}
 * positional argument, dispatches to {@link TakeDisposition} (explicit mode); bare, dispatches to
 * {@link TakeBareAuto} (auto mode). Either way, the resulting {@link TakeResult} is converted to a
 * process exit code via {@link TakeExitCodeMapper} and surfaced by throwing {@link
 * TakeExitCodeException} — never a direct {@code System.exit} call (project convention).
 *
 * <p>Pipeline load, the FR17 no-{@code tracker:}-section refusal, and tracker-adapter resolution
 * are delegated to {@link TakeCommandSupport} (kept out of this class purely for file size). Per
 * FR17, a project with no {@code tracker:} section refuses with a {@link UsageException} (exit 2)
 * before ever touching a tracker; once a {@link TrackerConfig} is present, a live {@link Tracker}
 * is resolved from the {@code trackerAdapterRegistry} (task 5.13's seam, {@link
 * TrackerAdapterFactory}) by {@link TrackerConfig#type()} — an unregistered type is likewise a
 * {@link UsageException} (exit 2); today that registry is empty for every type (task 5.15 wires
 * real adapters in), so this is the expected outcome for any project until then.
 *
 * <p>Short-ref expansion (`42`, `#42` via the configured binding, FR9) is handled by {@link
 * #resolveExplicitRef}: a recognized short ref is expanded via the registered {@link
 * TrackerAdapterFactory#expandRef} for {@code trackerConfig.type()}; an already-canonical ref is
 * wrapped as a {@link TaskRef#id()} unchanged.
 *
 * <p>Both a live {@link Tracker} and the {@link AbortHandler} built over it are resolved/constructed
 * here, at run time, once per invocation — never as Spring {@code @Bean}s (design: which tracker
 * adapter is active depends on the project's own config, read per invocation like {@link
 * PipelineDefinition} itself).
 *
 * <p>Not a Spring {@code @Component}: its {@link ManualRunAssembly} collaborator is
 * package-private and built manually, and {@code abortThreshold}/{@code taskIdMdcKey} are plain
 * primitives with no unambiguous bean to autowire — {@link ManualRunRunner} constructs this class
 * imperatively, exactly like it does {@link GitModeRunner}/{@link GitResumeRunner}.
 *
 * <p>Implements FR9, FR10, FR17, D4, D15, D16 of add-tracker-port.
 */
final class TakeCommand {

    private final TakeArgumentsParser argumentsParser = new TakeArgumentsParser();
    private final ManualRunAssembly assembly;
    private final Path worktreesRoot;
    private final String taskIdMdcKey;
    private final FactoryProperties factoryProperties;
    private final Clock clock;
    private final Map<String, TrackerAdapterFactory> trackerAdapterRegistry;

    /**
     * @param assembly the shared engine/ports assembly, reused from the manual-run path; never null
     * @param worktreesRoot the root directory under which per-task worktrees are created; never null
     * @param taskIdMdcKey the MDC key set once a resume bootstrap succeeds, matching {@link
     *     GitResumeRunner}'s own key
     * @param factoryProperties supplies the instance-name half of the minted {@link InstanceId}
     *     (design D5, D6) and the abort-backoff base/cap Duration defaults ({@code
     *     factory.tracker.abort-backoff-base}/{@code -cap}, design D5, D10); never null
     * @param clock supplies "now" for bare-mode's backoff filter and the {@link AbortHandler}'s
     *     abort timestamp; never null
     * @param trackerAdapterRegistry known tracker adapter factories, keyed by {@code tracker.type}
     *     (task 5.13's seam); an empty map in production until task 5.15 wires real adapters in
     */
    TakeCommand(
            ManualRunAssembly assembly,
            Path worktreesRoot,
            String taskIdMdcKey,
            FactoryProperties factoryProperties,
            Clock clock,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry) {
        this.assembly = assembly;
        this.worktreesRoot = worktreesRoot;
        this.taskIdMdcKey = taskIdMdcKey;
        this.factoryProperties = factoryProperties;
        this.clock = clock;
        this.trackerAdapterRegistry = trackerAdapterRegistry;
    }

    /**
     * Runs one {@code gnomish take} invocation to its terminal result and throws the corresponding
     * {@link TakeExitCodeException}.
     *
     * @param args the raw application arguments, including the leading {@code take} token
     * @throws UsageException if the flags are malformed (task 5.13's own flag matrix), the
     *     project has no {@code tracker:} section (FR17), or {@code tracker.type} names no
     *     registered adapter
     * @throws PipelineLoadFailedException if {@code .gnomish/} fails to load
     * @throws TakeExitCodeException always, on a completed run — carrying the exit code computed
     *     from the run's terminal {@link TakeResult} (design D16)
     */
    void run(ApplicationArguments args) throws IOException {
        try {
            TakeArguments takeArguments = argumentsParser.parse(args);
            PipelineDefinition definition = TakeCommandSupport.loadPipeline(takeArguments.dir());
            TrackerConfig trackerConfig = TakeCommandSupport.requireTrackerConfig(definition);
            InstanceId instanceId = InstanceId.generate(factoryProperties.instanceName());
            TrackerAdapterFactory factory = TakeCommandSupport.resolveFactory(trackerConfig, trackerAdapterRegistry);
            Tracker tracker = factory.create(trackerConfig, instanceId.value());
            List<String> credentialEnvVarsToScrub = factory.credentialEnvVars();

            String ref = takeArguments.ref();
            TakeResult result = ref == null
                    ? runBare(takeArguments, definition, trackerConfig, tracker, instanceId, credentialEnvVarsToScrub)
                    : runExplicit(
                            takeArguments,
                            ref,
                            definition,
                            trackerConfig,
                            tracker,
                            instanceId,
                            credentialEnvVarsToScrub);

            throw new TakeExitCodeException(TakeExitCodeMapper.exitCodeFor(result));
        } finally {
            MDC.remove(taskIdMdcKey);
        }
    }

    private TakeResult runExplicit(
            TakeArguments takeArguments,
            String rawRef,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            List<String> credentialEnvVarsToScrub) {
        // NFR-O1: the canonical ref is known as soon as short-ref expansion resolves it, before
        // fetchTask/dispose ever run — so every explicit-mode disposition outcome, including a
        // refusal (AwaitingHuman/Working/Finished/Gone in TakeDisposition, none of which reach any
        // deeper resume/fresh-claim MDC-setting code), is logged under the correct taskId.
        TaskRef ref = resolveExplicitRef(rawRef, trackerConfig);
        MDC.put(taskIdMdcKey, ref.id());
        TrackerTask trackerTask = tracker.fetchTask(ref);
        var disposition = new TakeDisposition(
                assembly,
                worktreesRoot,
                newAbortHandler(tracker),
                trackerConfig.abortThreshold(),
                taskIdMdcKey,
                credentialEnvVarsToScrub);
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
     * {@code trackerConfig.type()}; anything else (e.g. an already-canonical {@code
     * github:owner/repo#42}) is wrapped as-is, unchanged.
     *
     * @throws UsageException if {@code ref} looks like a short ref but no adapter factory is
     *     registered for {@code trackerConfig.type()} — expansion cannot silently fall back to
     *     treating the number as a literal canonical id
     */
    private TaskRef resolveExplicitRef(String ref, TrackerConfig trackerConfig) {
        if (!ShortRef.isShortRef(ref)) {
            return new TaskRef(ref);
        }
        TrackerAdapterFactory factory = trackerAdapterRegistry.get(trackerConfig.type());
        if (factory == null) {
            throw new UsageException("cannot expand short ref '" + ref + "': no tracker adapter registered for type '"
                    + trackerConfig.type() + "' — task 5.15 lands adapter wiring for this type");
        }
        return factory.expandRef(trackerConfig, ref);
    }

    private TakeResult runBare(
            TakeArguments takeArguments,
            PipelineDefinition definition,
            TrackerConfig trackerConfig,
            Tracker tracker,
            InstanceId instanceId,
            List<String> credentialEnvVarsToScrub) {
        FactoryProperties.Tracker trackerProperties = factoryProperties.tracker();
        var bareAuto = new TakeBareAuto(
                assembly,
                worktreesRoot,
                newAbortHandler(tracker),
                trackerConfig.abortThreshold(),
                taskIdMdcKey,
                trackerProperties.abortBackoffBase(),
                trackerProperties.abortBackoffCap(),
                clock,
                credentialEnvVarsToScrub);
        return bareAuto.run(takeArguments.dir(), definition, takeArguments.interactiveMode(), tracker, instanceId);
    }

    private AbortHandler newAbortHandler(Tracker tracker) {
        return new AbortHandler(tracker, clock);
    }
}
