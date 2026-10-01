package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook;
import com.github.oinsio.gnomish.app.lease.EpochRecordingTracker;
import com.github.oinsio.gnomish.app.port.git.BaseRefGit;
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Everything a command needs to reach the project's tracker, as one owner (design D2 of
 * collapse-composition-roots, the {@code TrackerWiring} row): the adapter registry keyed by {@code
 * tracker.type}, the credential seam the resolved adapter reads through, and the source the
 * project's definition — which names the type — is read from. The three arrive together and are
 * only ever used together, in one sequence: bind or load the definition → look up the adapter for
 * its type → build a live {@link Tracker} from it. That sequence lives here once; {@code take} and
 * {@code serve} used to spell it by hand, each holding the three as separate fields.
 *
 * <p>The credential seam never leaves this class (NFR-S1): there is no accessor for it, so a holder
 * of the wiring can have a tracker built with the secrets but cannot obtain them. Dispatch code
 * that needs only the ref checks takes the narrower {@link RefResolution} face.
 *
 * <p>A tracker is built per call, never cached: each invocation of a command resolves its own,
 * over its own tenure record, exactly as before this owner existed.
 *
 * <p>Implements FR1, FR4, NFR-S1 of collapse-composition-roots; FR9, FR17 of add-tracker-port;
 * FR4 of fix-claim-epoch-fence; FR13 of add-base-ref-resolution; FR10 of add-project-registry.
 */
@Component
final class TrackerWiring implements RefResolution {

    private final Map<String, TrackerAdapterFactory> registry;
    private final SecretsProvider secrets;
    private final PipelineSource pipelineSource;

    /**
     * @param registry known tracker adapter factories, keyed by {@code tracker.type}; never null
     * @param secrets the seam the resolved adapter reads its credentials through — supplied per
     *     call rather than captured in the factory, which {@code ServiceLoader} builds with no args
     *     (FR2, design D2 of add-plugin-architecture); never null
     * @param pipelineSource where the definition is read from; never null
     */
    TrackerWiring(Map<String, TrackerAdapterFactory> registry, SecretsProvider secrets, PipelineSource pipelineSource) {
        this.registry = registry;
        this.secrets = secrets;
        this.pipelineSource = pipelineSource;
    }

    /**
     * The source the startup definition came from, which every fresh claim reads its task tier
     * through as well (FR13 of add-base-ref-resolution): one registry, two reads. Exposed for the
     * run assembly to carry; it holds no credential.
     */
    PipelineSource pipelineSource() {
        return pipelineSource;
    }

    /**
     * Binds the trusted tier from the refreshed default branch of the clone at {@code dir} (FR13,
     * D14/D15 of add-base-ref-resolution) — see {@link TrustedTierStartup#bind}.
     *
     * @throws DefaultBranchUnboundException if the default branch cannot be established or refreshed
     * @throws PipelineLoadFailedException if the definition at the refreshed tip fails to load
     * @throws IOException if the law cannot be read at all
     */
    TrustedTierStartup.StartupLaw bindStartupLaw(Path dir, BaseRefGit baseRefs) throws IOException {
        return TrustedTierStartup.bind(dir, baseRefs, pipelineSource, registry);
    }

    /**
     * Resolves the registered {@link TrackerAdapterFactory} for {@code trackerConfig.type()} — the
     * single lookup {@link #resolveTracker} and the ref checks share, kept as one method so the
     * "unknown tracker type" refusal is worded identically everywhere it can be hit.
     *
     * @throws UsageException if no factory is registered for {@code trackerConfig.type()}
     */
    TrackerAdapterFactory resolveFactory(TrackerConfig trackerConfig) {
        TrackerAdapterFactory factory = registry.get(trackerConfig.type());
        if (factory == null) {
            throw new UsageException(
                    "unknown tracker type '" + trackerConfig.type() + "' — supported: " + supportedTypes());
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
     * @param factory the resolved adapter factory for the project's {@code tracker.type}; never null
     * @param instanceId this process's minted instance id, passed through to the factory's {@link
     *     TrackerAdapterFactory#create} (task 5.15 of add-tracker-port); never null
     * @param epochs the bundle's tenure record; never null
     */
    Tracker resolveTracker(
            TrackerAdapterFactory factory, TrackerConfig trackerConfig, InstanceId instanceId, ClaimEpochBook epochs) {
        return new EpochRecordingTracker(factory.create(secrets, trackerConfig, instanceId.value(), epochs), epochs);
    }

    /**
     * Everything a read-only tracker command ({@code board}, {@code dashboard}) needs from {@code
     * --dir}: load the working-tree pipeline, require its {@code tracker:} section and resolve a
     * plain {@link Tracker} under {@code readerId} — the throwaway id the command minted through
     * {@link ProjectScope#mintInstanceId} (design D8 of add-board-command — never written anywhere;
     * FR10 of add-project-registry). A reader is left unwrapped: it holds no tenure record and
     * never claims, so an {@link EpochRecordingTracker} would record nothing.
     *
     * @throws UsageException if the project has no {@code tracker:} section, or names an
     *     unregistered adapter type
     * @throws PipelineLoadFailedException if {@code .gnomish/} fails to load
     * @throws IOException if {@code .gnomish/} cannot be read (a genuine I/O fault)
     */
    ReadOnlyTrackerResolution resolveReadOnly(Path dir, InstanceId readerId) throws IOException {
        PipelineDefinition definition = TakeCommandSupport.loadPipeline(dir, pipelineSource);
        TrackerConfig trackerConfig = TakeCommandSupport.requireTrackerConfig(definition);
        Tracker tracker = resolveFactory(trackerConfig).create(secrets, trackerConfig, readerId.value());
        return new ReadOnlyTrackerResolution(trackerConfig, tracker);
    }

    /** The pair {@link #resolveReadOnly} resolves: the {@code tracker} section and the reader built from it. */
    record ReadOnlyTrackerResolution(TrackerConfig trackerConfig, Tracker tracker) {}

    @Override
    public TaskRef resolveRef(String rawRef, TrackerConfig trackerConfig) {
        if (!ShortRef.isShortRef(rawRef)) {
            return new TaskRef(rawRef);
        }
        TrackerAdapterFactory factory = registry.get(trackerConfig.type());
        if (factory == null) {
            throw new UsageException("cannot expand short ref '" + rawRef + "': unknown tracker type '"
                    + trackerConfig.type() + "' — supported: " + supportedTypes());
        }
        return factory.expandRef(trackerConfig, rawRef);
    }

    @Override
    public Optional<String> refuseForeignRef(TrackerAdapterFactory factory, TrackerConfig trackerConfig, TaskRef ref) {
        return factory.refuseForeignRef(secrets, trackerConfig, ref);
    }

    /**
     * The registered {@code tracker.type} keys as a stable, comma-separated list for the "unknown
     * tracker type" operator message — sorted so the hint reads the same on every run regardless
     * of registry iteration order (e.g. {@code "github, inmemory"}).
     */
    String supportedTypes() {
        return registry.keySet().stream().sorted().collect(Collectors.joining(", "));
    }
}
