package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The pipeline-load and tracker-resolution helpers {@link TakeCommand} needs before it can
 * dispatch (task 5.13): loading {@code .gnomish/} for {@code take} (mirroring {@link
 * PipelineStartup#load}, but narrower — {@code take} has no {@link RunArguments}, and no
 * {@code DirectoryWorkspace} is needed), the FR17 no-{@code tracker:}-section refusal, and
 * resolving a live {@link Tracker} from the {@link TrackerAdapterFactory} registry by {@code
 * tracker.type}. Split out of {@link TakeCommand} purely to keep that class within the project's
 * file-size target (`.claude/rules/process-invariants.md`).
 *
 * <p>Implements FR9, FR17 of add-tracker-port.
 */
final class TakeCommandSupport {

    private TakeCommandSupport() {}

    /**
     * Loads the pipeline definition from {@code dir}'s {@code .gnomish/}, once.
     *
     * @param dir the target project directory; never null
     * @return the validated, immutable pipeline model
     * @throws PipelineLoadFailedException if the tree fails to load
     * @throws IOException if {@code .gnomish/} cannot be read (a genuine I/O fault)
     */
    static PipelineDefinition loadPipeline(Path dir) throws IOException {
        LoadOutcome outcome = PipelineLoader.load(dir.resolve(".gnomish"));
        return switch (outcome) {
            case LoadOutcome.Loaded(var definition) -> definition;
            case LoadOutcome.Invalid(List<ConfigError> errors) ->
                throw new PipelineLoadFailedException(
                        errors.stream().map(ConfigError::render).toList());
        };
    }

    /**
     * FR17: an absent {@code tracker:} section means {@code take} is unavailable, {@code run}
     * unaffected.
     *
     * @param definition the loaded pipeline; never null
     * @return the project's {@code tracker} section
     * @throws UsageException if {@code definition.tracker()} is {@code null}
     */
    static TrackerConfig requireTrackerConfig(PipelineDefinition definition) {
        TrackerConfig trackerConfig = definition.tracker();
        if (trackerConfig == null) {
            throw new UsageException("'gnomish take' is unavailable: this project's .gnomish/config.yaml has no"
                    + " 'tracker' section (FR17) — add one to use tracker-driven tasks, or use 'gnomish run' instead");
        }
        return trackerConfig;
    }

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
            throw new UsageException("no tracker adapter registered for type '" + trackerConfig.type()
                    + "' — task 5.15 lands adapter wiring for this type");
        }
        return factory;
    }

    /**
     * Resolves a live {@link Tracker} from {@code registry} by {@code trackerConfig.type()} (task
     * 5.13's seam).
     *
     * @param trackerConfig the project's validated {@code tracker} section; never null
     * @param registry known tracker adapter factories, keyed by {@code tracker.type}; never null
     * @param instanceId this process's minted {@code InstanceId} value, passed through to the
     *     resolved factory's {@link TrackerAdapterFactory#create} (task 5.15); never null
     * @return a live {@link Tracker} for {@code trackerConfig.type()}
     * @throws UsageException if no factory is registered for {@code trackerConfig.type()}
     */
    static Tracker resolveTracker(
            TrackerConfig trackerConfig, Map<String, TrackerAdapterFactory> registry, String instanceId) {
        return resolveFactory(trackerConfig, registry).create(trackerConfig, instanceId);
    }
}
