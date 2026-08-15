package com.github.oinsio.gnomish.app.port.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Where a project's pipeline definition comes from: given the project directory, either the
 * validated {@link com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition} or the complete,
 * located problem list, as one {@link LoadOutcome}.
 *
 * <p>The application layer names only "the project directory". Everything below it — that the
 * definition lives in a {@code .gnomish/} subdirectory, that it is YAML, which files it spans, and
 * which per-adapter subsection validators take part — is the loading adapter's business ({@code
 * adapter.pipeline.GnomishDirPipelineSource}). Introduced by task 4.4 (FR12b, D12 of
 * split-into-modules): before it, every command threaded the composition root's {@code
 * tracker.type} → subsection-validator registry down to a direct {@code PipelineLoader} call.
 *
 * <p><b>Exception contract</b> (unchanged, FR8/D3 of load-pipeline-config): validation problems are
 * data, returned as {@link LoadOutcome.Invalid} and never thrown; only a genuine I/O fault — the
 * definition cannot be read at all — is an {@link IOException}.
 *
 * <p>Implements FR12b of split-into-modules; FR1, FR8 of load-pipeline-config.
 */
public interface PipelineSource {

    /**
     * Loads {@code projectDir}'s pipeline definition.
     *
     * @param projectDir the project root (the {@code --dir} / {@code --project} directory), not the
     *     definition directory itself; never null
     * @return the validated definition, or every located problem found in one pass; never null
     * @throws IOException if the definition cannot be read — an I/O fault, never a validation
     *     problem
     */
    LoadOutcome load(Path projectDir) throws IOException;
}
