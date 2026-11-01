package com.github.oinsio.gnomish.app;

import java.util.List;

/**
 * The describe preflight of a manual {@code run} (design D5 of define-executor-contract, as revised
 * 2026-10-31): a law that could not be described fails the invocation as an invalid law does — the
 * located errors printed for the author, through {@link PipelineLoadFailedException} — since a
 * {@code run} has no tracker to park in. Every manual path — fresh and resume, in place, git mode on
 * the host and in a box — asks here, so the four fail alike.
 *
 * <p>Implements FR4, FR13, UX1 of define-executor-contract.
 */
final class ManualRunDescribe {

    private ManualRunDescribe() {}

    /**
     * Describes the run's law in {@code medium}.
     *
     * @return the described law; never null
     * @throws PipelineLoadFailedException when the law could not be described
     */
    static DescribedLaw described(RunAssembly assembly, RunOrder order, DescribeMedium medium, LawBinding binding) {
        return switch (assembly.describe(order, medium, List.of(), binding)) {
            case DescribedLaw law -> law;
            case LawDescription.Refused(var errors) ->
                throw new PipelineLoadFailedException(PipelineStartup.render(errors));
        };
    }
}
