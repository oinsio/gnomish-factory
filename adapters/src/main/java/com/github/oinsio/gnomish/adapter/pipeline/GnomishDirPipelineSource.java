package com.github.oinsio.gnomish.adapter.pipeline;

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
 * <p>Implements FR12b of split-into-modules; FR1, FR8 of load-pipeline-config; FR17 of
 * add-tracker-port.
 *
 * @param trackerValidatorRegistry the {@code tracker.type} → subsection validator registry; never
 *     null, possibly empty (no adapter contributes a validator)
 */
public record GnomishDirPipelineSource(Map<String, TrackerSubsectionValidator> trackerValidatorRegistry)
        implements PipelineSource {

    /** The definition directory, relative to the project root. */
    private static final String DEFINITION_DIR = ".gnomish";

    public GnomishDirPipelineSource {
        trackerValidatorRegistry = Map.copyOf(trackerValidatorRegistry);
    }

    @Override
    public LoadOutcome load(Path projectDir) throws IOException {
        return PipelineLoader.load(projectDir.resolve(DEFINITION_DIR), trackerValidatorRegistry);
    }
}
