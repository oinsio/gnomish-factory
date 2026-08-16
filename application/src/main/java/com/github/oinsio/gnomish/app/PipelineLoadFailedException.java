package com.github.oinsio.gnomish.app;

import java.io.Serial;
import java.util.List;

/**
 * Wraps a {@link PipelineLoadOutcome.Failed} as a thrown exception: the future runner
 * entrypoint (tasks 7.10/7.11) throws this after {@link PipelineStartup#load} returns
 * {@code Failed}, so {@link RunExitCodeMapper} can route it to the pipeline-load-failure
 * exit code (3) at the CLI boundary. {@code renderedErrors} carries every {@link
 * com.github.oinsio.gnomish.domain.pipeline.ConfigError#render()} line, ready to print
 * as-is (FR1, FR12).
 *
 * <p>Unchecked: this is a boundary-crossing signal, not a recoverable condition callers
 * are expected to catch mid-flow.
 *
 * <p>Implements FR12, D10 of add-manual-run.
 */
public final class PipelineLoadFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final List<String> renderedErrors;

    /**
     * Constructs the exception with the given rendered loader-error lines.
     *
     * @param renderedErrors one rendered line per loader error; never empty
     */
    public PipelineLoadFailedException(List<String> renderedErrors) {
        super(String.join("\n", renderedErrors));
        this.renderedErrors = List.copyOf(renderedErrors);
    }

    /**
     * Returns every rendered loader-error line, in aggregation order; never empty.
     *
     * @return the rendered loader-error lines
     */
    public List<String> renderedErrors() {
        return renderedErrors;
    }
}
