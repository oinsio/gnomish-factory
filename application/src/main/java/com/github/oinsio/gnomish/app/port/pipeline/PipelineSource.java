package com.github.oinsio.gnomish.app.port.pipeline;

import com.github.oinsio.gnomish.app.LawBinding;
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
 * <p><b>Two ways in, one loader.</b> {@link #load(Path)} reads the working tree of a project
 * directory — the in-place mode, manual {@code run} without {@code --base}, {@code board} and
 * {@code dashboard}. The binding forms read the law a {@link LawBinding} names — git objects at a
 * revision for every path that resolved a ref (design D12 of add-base-ref-resolution): {@link
 * #bindConfiguration} is the startup read of both tiers from the refreshed default branch, {@link
 * #bindTaskTier} the per-task read of the task tier alone from the task's law commit (D14, D15).
 *
 * <p>Implements FR12b of split-into-modules; FR1, FR8 of load-pipeline-config; FR2, FR13 of
 * add-base-ref-resolution.
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

    /**
     * Reads both configuration tiers from the law {@code binding} names: the startup read of
     * {@code serve}/{@code take}, which validates the whole definition, binds the trusted tier for
     * the process lifetime, and runs the designator seam against the configured adapter's answer.
     *
     * @param binding which repository and revision the law is read from; never null
     * @param designatorKinds how the loader learns which designator kinds the configured adapter
     *     extracts, once it has mapped the {@code tracker} section; never null
     * @return both tiers and the law commit; never null
     * @throws IOException if the law cannot be read at all — an I/O fault, never a validation
     *     problem
     */
    BoundConfiguration bindConfiguration(LawBinding binding, ConfiguredDesignatorKinds designatorKinds)
            throws IOException;

    /**
     * Reads the task tier from the law {@code binding} names: the per-task read after base
     * resolution, whose result — or located problems — governs that task and no other.
     *
     * @param binding which repository and revision the task's law is read from; never null
     * @return the task tier and the law commit; never null
     * @throws IOException if the law cannot be read at all — an I/O fault, never a validation
     *     problem
     */
    BoundTaskTier bindTaskTier(LawBinding binding) throws IOException;
}
