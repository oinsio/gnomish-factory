package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook;
import com.github.oinsio.gnomish.app.lease.EpochRecordingTracker;
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves a live {@link Tracker} from the {@link TrackerAdapterFactory} registry by {@code
 * tracker.type} (task 5.13's seam): the claiming funnel {@link #resolveTracker}, the claimless
 * lookup {@link #resolveReadOnlyTracker} that {@code board} and {@code dashboard} use, and the
 * {@code --dir}-to-resolved-tracker convenience {@link #resolveReadOnlyTrackerFromDir} both of
 * those commands share. Split out of {@link TakeCommandSupport} purely to keep that class within
 * the project's file-size target (`.claude/rules/process-invariants.md`) — the two classes have no
 * runtime relationship beyond sharing a package.
 *
 * <p>Implements FR9, FR17 of add-tracker-port.
 */
final class TrackerResolution {

    private TrackerResolution() {}

    /**
     * Resolves the registered {@link TrackerAdapterFactory} for {@code trackerConfig.type()} (task
     * 5.13's seam), the single lookup {@link #resolveTracker} and {@link TakeCommand}'s own
     * short-ref expansion both need — kept as one method so the "no adapter registered" refusal is
     * worded identically everywhere it can be hit.
     *
     * @param trackerConfig the project's validated {@code tracker} section; never null
     * @param registry known tracker adapter factories, keyed by {@code tracker.type}; never null
     * @return the registered factory for {@code trackerConfig.type()}
     * @throws UsageException if no factory is registered for {@code trackerConfig.type()}
     */
    static TrackerAdapterFactory resolveFactory(
            TrackerConfig trackerConfig, Map<String, TrackerAdapterFactory> registry) {
        TrackerAdapterFactory factory = registry.get(trackerConfig.type());
        if (factory == null) {
            throw new UsageException(
                    "unknown tracker type '" + trackerConfig.type() + "' — supported: " + supportedTypes(registry));
        }
        return factory;
    }

    /**
     * The one funnel a claiming command resolves its tracker through (FR4, design D2 of
     * fix-claim-epoch-fence): the adapter is built over {@code epochs} so its own writers stamp the
     * tenure they write under, and the result is wrapped in {@link EpochRecordingTracker} over the
     * same book, so the claim that fills the record and the writers that read it can never be two
     * different books. {@code epochs} comes from the bundle the command was handed ({@code
     * git.epochs()}) — the record its git writers already stamp from.
     *
     * <p>Before this, the wrapping lived in a composition-root bean over the whole registry. That
     * held for production and for nothing else: every assembly built by hand — every end-to-end
     * fixture — bypassed the bean, so its claims recorded nothing and its commits carried no stamp,
     * and no spec could see the classification defect this change removes.
     *
     * @param factory the resolved adapter factory for the project's {@code tracker.type}; never null
     * @param trackerConfig the project's validated {@code tracker} section; never null
     * @param secrets the seam the resolved adapter reads its credentials through — supplied here
     *     rather than captured in the factory, which {@code ServiceLoader} builds with no args
     *     (FR2, design D2 of add-plugin-architecture); never null
     * @param instanceId this process's minted {@code InstanceId} value, passed through to the
     *     resolved factory's {@link TrackerAdapterFactory#create} (task 5.15); never null
     * @param epochs the bundle's tenure record, filled by the returned decorator and read by the
     *     writers that stamp commits of the same tenure; never null
     * @return a live, epoch-recording {@link Tracker} for {@code trackerConfig.type()}
     */
    static Tracker resolveTracker(
            TrackerAdapterFactory factory,
            TrackerConfig trackerConfig,
            SecretsProvider secrets,
            String instanceId,
            ClaimEpochBook epochs) {
        return new EpochRecordingTracker(factory.create(secrets, trackerConfig, instanceId, epochs), epochs);
    }

    /**
     * Resolves a live {@link Tracker} from {@code registry} by {@code trackerConfig.type()} (task
     * 5.13's seam) for a command that never claims — {@code board} and {@code dashboard}, which
     * read the tracker and write nothing. They hold no {@link
     * com.github.oinsio.gnomish.app.port.git.TaskGit} bundle and therefore no tenure record, and
     * wrapping a reader in {@link EpochRecordingTracker} would record nothing anyway: the decorator
     * only ever observes a claim. A command that DOES claim resolves through {@link
     * #resolveTracker} instead.
     *
     * @param trackerConfig the project's validated {@code tracker} section; never null
     * @param registry known tracker adapter factories, keyed by {@code tracker.type}; never null
     * @param secrets the seam the resolved adapter reads its credentials through; never null
     * @param instanceId this process's minted {@code InstanceId} value; never null
     * @return a live {@link Tracker} for {@code trackerConfig.type()}
     * @throws UsageException if no factory is registered for {@code trackerConfig.type()}
     */
    static Tracker resolveReadOnlyTracker(
            TrackerConfig trackerConfig,
            Map<String, TrackerAdapterFactory> registry,
            SecretsProvider secrets,
            String instanceId) {
        return resolveFactory(trackerConfig, registry).create(secrets, trackerConfig, instanceId);
    }

    /**
     * The {@code board} and {@code dashboard} pair for "resolve everything a read-only tracker
     * command needs from {@code --dir}": load the working-tree pipeline, require its {@code
     * tracker:} section, mint the throwaway {@link InstanceId} (design D8 — never written
     * anywhere), and resolve the read-only {@link Tracker}. Both commands ran this exact sequence
     * by hand; folded here so it has one owner instead of two copies kept in sync manually.
     *
     * @param dir the target project directory; never null
     * @param pipelineSource where the definition is loaded from; never null
     * @param factoryProperties this instance's identity, the source of the minted {@link
     *     InstanceId}; never null
     * @param registry known tracker adapter factories, keyed by {@code tracker.type}; never null
     * @param secrets the seam the resolved adapter reads its credentials through; never null
     * @return the project's {@code tracker} section paired with its resolved, read-only {@link
     *     Tracker}
     * @throws UsageException if the project has no {@code tracker:} section, or names an
     *     unregistered adapter type
     * @throws PipelineLoadFailedException if {@code .gnomish/} fails to load
     * @throws IOException if {@code .gnomish/} cannot be read (a genuine I/O fault)
     */
    static ReadOnlyTrackerResolution resolveReadOnlyTrackerFromDir(
            Path dir,
            PipelineSource pipelineSource,
            FactoryProperties factoryProperties,
            Map<String, TrackerAdapterFactory> registry,
            SecretsProvider secrets)
            throws IOException {
        PipelineDefinition definition = TakeCommandSupport.loadPipeline(dir, pipelineSource);
        TrackerConfig trackerConfig = TakeCommandSupport.requireTrackerConfig(definition);
        InstanceId instanceId = InstanceId.generate(factoryProperties.instanceName());
        Tracker tracker = resolveReadOnlyTracker(trackerConfig, registry, secrets, instanceId.value());
        return new ReadOnlyTrackerResolution(trackerConfig, tracker);
    }

    /**
     * The pair {@link #resolveReadOnlyTrackerFromDir} resolves: the project's {@code tracker}
     * section and the read-only {@link Tracker} built from it.
     */
    record ReadOnlyTrackerResolution(TrackerConfig trackerConfig, Tracker tracker) {}

    /**
     * Renders the registered {@code tracker.type} keys as a stable, comma-separated list for the
     * "unknown tracker type" operator message — sorted so the hint reads the same on every run
     * regardless of registry iteration order.
     *
     * @param registry known tracker adapter factories, keyed by {@code tracker.type}; never null
     * @return the sorted type keys joined by {@code ", "} (e.g. {@code "github, inmemory"})
     */
    static String supportedTypes(Map<String, TrackerAdapterFactory> registry) {
        return registry.keySet().stream().sorted().collect(Collectors.joining(", "));
    }
}
