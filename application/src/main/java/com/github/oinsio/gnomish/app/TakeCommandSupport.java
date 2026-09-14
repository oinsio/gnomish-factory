package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.LivenessVerdict;
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource;
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;

/**
 * The pipeline-load helpers the tracker-facing commands need before they can dispatch (task
 * 5.13): the working-tree load {@code board} and {@code dashboard} keep (mirroring {@link
 * PipelineStartup#load}, but narrower), the startup sandbox-lifecycle sweep, and the FR17
 * no-{@code tracker:}-section refusal. The startup load of {@code take} and {@code serve} moved
 * to {@link TrustedTierStartup} (FR13 of add-base-ref-resolution). Resolving a live {@link
 * com.github.oinsio.gnomish.app.port.tracker.Tracker} from the {@link TrackerAdapterFactory}
 * registry moved to {@link TrackerResolution}, split out purely to keep this class within the
 * project's file-size target (`.claude/rules/process-invariants.md`).
 *
 * <p>Implements FR17 of add-tracker-port.
 */
final class TakeCommandSupport {

    private TakeCommandSupport() {}

    /**
     * Loads the pipeline definition from {@code dir}'s working-tree {@code .gnomish/}, once — the
     * read {@code board} and {@code dashboard} display from, deliberately unchanged by
     * add-base-ref-resolution.
     *
     * @param dir the target project directory; never null
     * @param pipelineSource where the definition is loaded from; the configured realization also
     *     rejects a malformed {@code tracker.<type>} subsection at load time (FR17) exactly as
     *     {@code run} does; never null
     * @return the validated, immutable pipeline model
     * @throws PipelineLoadFailedException if the tree fails to load
     * @throws IOException if the definition cannot be read (a genuine I/O fault)
     */
    static PipelineDefinition loadPipeline(Path dir, PipelineSource pipelineSource) throws IOException {
        LoadOutcome outcome = pipelineSource.load(dir);
        return switch (outcome) {
            case LoadOutcome.Loaded(var definition) -> definition;
            case LoadOutcome.Invalid(List<ConfigError> errors) ->
                throw new PipelineLoadFailedException(
                        errors.stream().map(ConfigError::render).toList());
        };
    }

    /**
     * Runs the startup sweep-lifecycle pass and logs its one-line summary (FR6, NFR-O4 of
     * add-serve-sandbox-lifecycle). The summary is logged rather than carried into the task's
     * finish report: a {@code take} finish report describes ONE task, while the sweep is
     * project-wide and mostly concerns objects of other tasks.
     *
     * <p>Every failure is swallowed with a log line — a Docker outage aborts the pass (NFR-R1),
     * and neither that nor any other sweep fault is a reason to fail a take that has not even
     * claimed a task yet.
     *
     * @param pass the sweep-lifecycle evaluation seam; never null
     * @param dir the target project directory the project identity is resolved from; never null
     * @param liveness the tracked-object liveness verdict of this invocation; never null
     * @param log the caller's logger, so the line reads as {@code take}'s own; never null
     */
    static void sweepSandboxLifecycle(SandboxLifecyclePass pass, Path dir, LivenessVerdict liveness, Logger log) {
        try {
            String summary = pass.run(dir, liveness);
            if (!summary.isBlank()) {
                log.info("gnomish take: {}", summary);
            }
        } catch (RuntimeException e) {
            log.info("gnomish take: sandbox lifecycle sweep skipped", e);
        }
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
}
