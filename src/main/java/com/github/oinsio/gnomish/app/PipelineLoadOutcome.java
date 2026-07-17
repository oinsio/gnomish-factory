package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.util.List;

/**
 * The result of {@link PipelineStartup#load}: either the pipeline definition loaded from
 * {@code --project}'s {@code .gnomish/} together with the constructed workspace, or the
 * loader's problems rendered as text lines, ready to print as-is before exit (FR1, FR12,
 * design D3).
 *
 * <p>Deliberately a typed value rather than a print + exit inside {@link PipelineStartup}:
 * exit-code mapping (3, for {@link Failed}) is task 7.9's job, via Spring Boot's
 * {@code ExitCodeGenerator}/{@code ExitCodeExceptionMapper} (design D10) — this type is the
 * seam the eventual runner loop (task 7.4+) interprets and acts on.
 *
 * <p>Implements FR1, FR12, D3 of add-manual-run.
 */
public sealed interface PipelineLoadOutcome {

    /**
     * The pipeline definition loaded cleanly from {@code --project}'s {@code .gnomish/}.
     *
     * <p>Implements FR1, D3 of add-manual-run.
     *
     * @param definition the validated, immutable pipeline model
     * @param workspace the workspace constructed from {@code --project}, for the runner to
     *     proceed with (FR1, D3)
     */
    record Loaded(PipelineDefinition definition, DirectoryWorkspace workspace) implements PipelineLoadOutcome {}

    /**
     * The {@code .gnomish/} tree failed to load: every {@link com.github.oinsio.gnomish.domain
     * .pipeline.ConfigError#render()} line from the loader, in aggregation order, ready to
     * print as-is (FR1, FR12).
     *
     * <p>Implements FR1, FR12, D3 of add-manual-run.
     *
     * @param renderedErrors one rendered line per loader error; never empty
     */
    record Failed(List<String> renderedErrors) implements PipelineLoadOutcome {

        public Failed {
            renderedErrors = List.copyOf(renderedErrors);
        }
    }
}
