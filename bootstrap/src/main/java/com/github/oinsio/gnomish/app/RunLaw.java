package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.check.PinCheckedExternalCheckClient;
import com.github.oinsio.gnomish.adapter.law.LawSource;
import com.github.oinsio.gnomish.adapter.law.LawSources;
import com.github.oinsio.gnomish.adapter.law.PipelineLaw;
import com.github.oinsio.gnomish.adapter.law.PipelineLawReader;
import com.github.oinsio.gnomish.app.port.check.ExternalCheckPinContributor;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import org.jspecify.annotations.Nullable;

/**
 * One run's law, opened: the seam where a {@link LawBinding} becomes the bare-object reader of
 * its repository, the {@link LawSource} its files are read through, the frozen {@link
 * PipelineLaw}, and the external-check pin guard that vouches for it — all from one peel of the
 * binding's revision (design D12 of add-base-ref-resolution). Extracted from {@code RunAssembler}
 * as the owner of this responsibility: the assembler wires executors, checks and consoles, and
 * asks this class for the law and the guard instead of resolving either itself.
 *
 * <p>Law reads and pin reads share the one {@link GitObjects} opened here, over the binding's own
 * repository root, so the pin paths — repository-relative by design — and the law commit are read
 * from the same object store by construction.
 *
 * <p>Implements FR11, M5 of add-base-ref-resolution; FR16, D10 of add-sandbox-core.
 */
final class RunLaw {

    private final LawSource source;
    private final GitObjects gitObjects;
    private final @Nullable ObjectId lawCommit;

    private RunLaw(LawSource source, GitObjects gitObjects, @Nullable ObjectId lawCommit) {
        this.source = source;
        this.gitObjects = gitObjects;
        this.lawCommit = lawCommit;
    }

    /**
     * Opens the law {@code binding} names: the repository's bare objects, the source for the
     * binding's medium, and the law commit peeled exactly once.
     *
     * <p>Implements FR11, M5 of add-base-ref-resolution.
     *
     * @param binding which repository and tree this run's law is bound to
     * @return the opened law; never null
     * @throws UsageException when the binding names a revision its repository resolves to no commit
     */
    static RunLaw open(LawBinding binding) {
        var gitObjects = LawSources.gitObjectsOf(binding.repositoryRoot());
        var bound = LawSources.open(binding, gitObjects);
        return new RunLaw(bound.source(), gitObjects, bound.lawCommit());
    }

    /**
     * Freezes the {@code definition}'s law files in memory at invocation start (D14, FR19 of
     * add-sandbox-core), so a later edit to the same file has no effect on the running task.
     *
     * @param definition the loaded pipeline whose instructions and criteria are captured
     * @return the frozen law; never null
     */
    PipelineLaw freeze(PipelineDefinition definition) {
        return PipelineLawReader.freeze(source, definition);
    }

    /**
     * Wraps {@code client} in the {@link PinCheckedExternalCheckClient} (FR16, D10 of
     * add-sandbox-core) comparing against the very commit this law was opened at: the law commit's
     * own SHA wherever a ref was resolved, the checked-out commit where the law is the working tree.
     * The in-place mode's workspace may not be a git repository at all, in which case a check that
     * declares pin paths degrades fail-closed to CannotVerify while a pinless interactive check
     * passes vacuously.
     *
     * @param client the selected external-check client the guard fronts
     * @param contributor the client's pin-path contribution seam
     * @return the pin-guarded client; never null
     */
    ExternalCheckClient pinGuarded(ExternalCheckClient client, ExternalCheckPinContributor contributor) {
        return new PinCheckedExternalCheckClient(client, contributor, gitObjects, lawCommit);
    }
}
