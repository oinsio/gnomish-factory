package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.adapter.law.LawSources;
import com.github.oinsio.gnomish.adapter.law.LawSources.BoundLaw;
import com.github.oinsio.gnomish.app.CheckParamsValidator;
import com.github.oinsio.gnomish.app.ConnectionProfiles;
import com.github.oinsio.gnomish.app.LawBinding;
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.app.port.pipeline.BoundConfiguration;
import com.github.oinsio.gnomish.app.port.pipeline.BoundTaskTier;
import com.github.oinsio.gnomish.app.port.pipeline.ConfiguredDesignatorKinds;
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource;
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * The {@link PipelineSource} realization every command runs on: the project's definition is the
 * {@code .gnomish/} subdirectory of the project root, loaded by {@link PipelineLoader}.
 *
 * <p>Holds the composition root's {@code tracker.type} → subsection-validator registry ({@code
 * TrackerAdapterConfiguration}) so a malformed {@code tracker.<type>} subsection is a located load
 * error (FR17 of add-tracker-port) rather than surfacing only later. That registry used to be
 * threaded through every command down to the {@code PipelineLoader} call site; task 4.4 (FR12b,
 * D12 of split-into-modules) closes it over here instead, leaving the application layer with the
 * port alone.
 *
 * <p>It closes the discovered check-provider registry over the load the same way (FR6, FR13 of
 * add-plugin-architecture), so an {@code external} check naming a provider no jar serves is a
 * located load error in every run mode — the composition root holds the registry, and no command
 * has to thread it down either.
 *
 * <p>It closes the factory's configured check providers over the load as well (FR3, design D3 of
 * remove-interactive-console): an {@code external} check on a provider with no {@code
 * factory.check.<provider>} section is a located load error on every path that will run the
 * pipeline. {@link #loadReadOnly} is the one read that does not grade it — a command that only
 * shows a project's state runs no check, so treats every discovered provider as configured.
 *
 * <p>The binding forms (FR2, FR13, design D12–D15 of add-base-ref-resolution) open the law a
 * {@link LawBinding} names through {@link LawSources} — git objects at the peeled law commit for a
 * resolved revision — and run the same one-pass loader over it, with the same registries and
 * profiles closed over, so a definition that loads from the working tree loads identically from a
 * commit. {@link #bindConfiguration} is the startup read of both tiers; {@link #bindTaskTier} the
 * per-task read of the task tier alone.
 *
 * <p>Implements FR12b of split-into-modules; FR1, FR8 of load-pipeline-config; FR17 of
 * add-tracker-port; FR6, FR13 of add-plugin-architecture; FR2, FR13 of add-base-ref-resolution.
 *
 * @param trackerValidatorRegistry the {@code tracker.type} → subsection validator registry; never
 *     null, possibly empty (no adapter contributes a validator)
 * @param checkProviderRegistry the {@code provider} → check-params-validator registry, keyed by
 *     every discovered check provider; never null, possibly empty (no provider discovered)
 * @param configuredCheckProviders the providers with a {@code factory.check.<provider>} section in
 *     this factory's configuration; never null, possibly empty (none configured)
 * @param connectionProfiles the operator-declared {@code factory.connections} profiles a {@code
 *     tracker.<type>} subsection may reference as {@code connection: <name>} (FR16, design D8/D12 of
 *     add-plugin-architecture); closed over here for the same reason as the registries — the
 *     profiles live in operator configuration, the subsection in the project repo, and only the
 *     composition root sees both
 */
public record GnomishDirPipelineSource(
        Map<String, TrackerSubsectionValidator> trackerValidatorRegistry,
        Map<String, CheckParamsValidator> checkProviderRegistry,
        Set<String> configuredCheckProviders,
        ConnectionProfiles connectionProfiles)
        implements PipelineSource {

    public GnomishDirPipelineSource {
        trackerValidatorRegistry = Map.copyOf(trackerValidatorRegistry);
        checkProviderRegistry = Map.copyOf(checkProviderRegistry);
        configuredCheckProviders = Set.copyOf(configuredCheckProviders);
    }

    /** Convenience for the callers predating named connection profiles: no profile is defined. */
    public GnomishDirPipelineSource(
            Map<String, TrackerSubsectionValidator> trackerValidatorRegistry,
            Map<String, CheckParamsValidator> checkProviderRegistry,
            Set<String> configuredCheckProviders) {
        this(trackerValidatorRegistry, checkProviderRegistry, configuredCheckProviders, ConnectionProfiles.none());
    }

    @Override
    public LoadOutcome load(Path projectDir) throws IOException {
        return loadGrading(projectDir, configuredCheckProviders);
    }

    @Override
    public LoadOutcome loadReadOnly(Path projectDir) throws IOException {
        return loadGrading(projectDir, checkProviderRegistry.keySet());
    }

    private LoadOutcome loadGrading(Path projectDir, Set<String> configured) throws IOException {
        return PipelineLoader.load(
                projectDir.resolve(LawBinding.LAW_ROOT),
                trackerValidatorRegistry,
                checkProviderRegistry,
                configured,
                connectionProfiles);
    }

    @Override
    public BoundConfiguration bindConfiguration(LawBinding binding, ConfiguredDesignatorKinds designatorKinds)
            throws IOException {
        BoundLaw law = openLaw(binding);
        ConfigurationLoad load = PipelineLoader.loadConfiguration(
                law.source(),
                trackerValidatorRegistry,
                checkProviderRegistry,
                configuredCheckProviders,
                connectionProfiles,
                designatorKinds);
        return new BoundConfiguration(load.outcome(), load.base(), lawCommitOf(law, binding));
    }

    @Override
    public BoundTaskTier bindTaskTier(LawBinding binding) throws IOException {
        BoundLaw law = openLaw(binding);
        LoadOutcome outcome = PipelineLoader.loadConfiguration(
                        law.source(),
                        trackerValidatorRegistry,
                        checkProviderRegistry,
                        configuredCheckProviders,
                        connectionProfiles,
                        ConfiguredDesignatorKinds.NONE)
                .outcome();
        return new BoundTaskTier(outcome, lawCommitOf(law, binding));
    }

    private static BoundLaw openLaw(LawBinding binding) {
        return LawSources.open(binding, LawSources.gitObjectsOf(binding.repositoryRoot()));
    }

    /**
     * A bound read always names its commit: a resolved revision peels to one, and a working-tree
     * binding pins its checkout. The one binding with no commit — a working tree that is no
     * repository, the in-place mode's workspace — has no law commit to report or to pin, and the
     * path form {@link #load(Path)} is the read it gets.
     */
    private static ObjectId lawCommitOf(BoundLaw law, LawBinding binding) {
        ObjectId lawCommit = law.lawCommit();
        if (lawCommit == null) {
            throw new IllegalStateException("cannot bind the pipeline law at " + binding.repositoryRoot()
                    + ": the working tree is no git repository, so it names no law commit — load it by path instead");
        }
        return lawCommit;
    }
}
