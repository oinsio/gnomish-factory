package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.pipeline.PipelineLoader;
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Loads the pipeline definition from {@code --project}'s {@code .gnomish/} exactly once at
 * startup, before any dialog (FR1, FR12, design D3): constructs the {@link DirectoryWorkspace}
 * from {@link RunArguments#project()}, then calls {@link PipelineLoader#load} on
 * {@code project/.gnomish}.
 *
 * <p>This class stays pure with respect to process concerns: it neither prints nor exits.
 * An invalid tree is reported back as {@link PipelineLoadOutcome.Failed}, carrying every
 * {@link ConfigError#render()} line — "loader errors printed as-is" (spec) describes the
 * shape of that data, not an action taken here; the eventual runner loop (task 7.4+) prints
 * it and exits 3 (task 7.9, design D10, via Boot's {@code ExitCodeGenerator}). A
 * non-existent {@code --project} ({@link DirectoryWorkspace}'s {@link IllegalArgumentException})
 * and a genuine I/O fault reading {@code .gnomish/} ({@link IOException}) both propagate
 * unchanged — classifying them into exit codes is likewise task 7.9's concern.
 *
 * <p>Implements FR1, FR12, D3 of add-manual-run.
 */
@Component
public final class PipelineStartup {

    /**
     * Loads the pipeline definition named by {@code args.project()}'s {@code .gnomish/}
     * subdirectory, once.
     *
     * <p>Implements FR1, FR12, D3 of add-manual-run.
     *
     * @param args the parsed and first-tier-validated run arguments (task 7.1)
     * @return {@link PipelineLoadOutcome.Loaded} with the definition and workspace when the
     *     tree is valid, else {@link PipelineLoadOutcome.Failed} with the rendered errors
     * @throws IllegalArgumentException if {@code args.project()} is not a pre-existing
     *     directory ({@link DirectoryWorkspace})
     * @throws IOException if {@code .gnomish/} cannot be read (a genuine I/O fault, not a
     *     validation problem — {@link PipelineLoader})
     */
    public PipelineLoadOutcome load(RunArguments args) throws IOException {
        DirectoryWorkspace workspace = new DirectoryWorkspace(args.project());
        LoadOutcome outcome = PipelineLoader.load(args.project().resolve(".gnomish"));
        return switch (outcome) {
            case LoadOutcome.Loaded(var definition) -> new PipelineLoadOutcome.Loaded(definition, workspace);
            case LoadOutcome.Invalid(List<ConfigError> errors) -> new PipelineLoadOutcome.Failed(render(errors));
        };
    }

    private static List<String> render(List<ConfigError> errors) {
        return errors.stream().map(ConfigError::render).toList();
    }
}
