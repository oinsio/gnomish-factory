package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.app.CheckParamsValidator;
import com.github.oinsio.gnomish.app.ConnectionProfiles;
import com.github.oinsio.gnomish.app.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource;
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

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
 * <p>Implements FR12b of split-into-modules; FR1, FR8 of load-pipeline-config; FR17 of
 * add-tracker-port; FR6, FR13 of add-plugin-architecture.
 *
 * @param trackerValidatorRegistry the {@code tracker.type} → subsection validator registry; never
 *     null, possibly empty (no adapter contributes a validator)
 * @param checkProviderRegistry the {@code provider} → check-params-validator registry, keyed by
 *     every discovered check provider; never null, possibly empty (no provider discovered)
 * @param connectionProfiles the operator-declared {@code factory.connections} profiles a {@code
 *     tracker.<type>} subsection may reference as {@code connection: <name>} (FR16, design D8/D12 of
 *     add-plugin-architecture); closed over here for the same reason as the registries — the
 *     profiles live in operator configuration, the subsection in the project repo, and only the
 *     composition root sees both
 */
public record GnomishDirPipelineSource(
        Map<String, TrackerSubsectionValidator> trackerValidatorRegistry,
        Map<String, CheckParamsValidator> checkProviderRegistry,
        ConnectionProfiles connectionProfiles)
        implements PipelineSource {

    /** The definition directory, relative to the project root. */
    private static final String DEFINITION_DIR = ".gnomish";

    public GnomishDirPipelineSource {
        trackerValidatorRegistry = Map.copyOf(trackerValidatorRegistry);
        checkProviderRegistry = Map.copyOf(checkProviderRegistry);
    }

    /** Convenience for the callers predating named connection profiles: no profile is defined. */
    public GnomishDirPipelineSource(
            Map<String, TrackerSubsectionValidator> trackerValidatorRegistry,
            Map<String, CheckParamsValidator> checkProviderRegistry) {
        this(trackerValidatorRegistry, checkProviderRegistry, ConnectionProfiles.none());
    }

    @Override
    public LoadOutcome load(Path projectDir) throws IOException {
        return PipelineLoader.load(
                projectDir.resolve(DEFINITION_DIR),
                trackerValidatorRegistry,
                checkProviderRegistry,
                connectionProfiles);
    }
}
