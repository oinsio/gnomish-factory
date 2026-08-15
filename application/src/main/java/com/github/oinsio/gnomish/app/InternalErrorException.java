package com.github.oinsio.gnomish.app;

import java.io.Serial;

/**
 * An engine-internal error the runner cannot recover from in-process: currently raised only for
 * {@link com.github.oinsio.gnomish.domain.engine.EscalationReport.PipelineMismatch}, which the
 * spec calls "unreachable in-process" — it can only happen if {@code .gnomish/} changes between
 * load and run in a way the loop never re-validates against (FR9). The message carries the
 * already-rendered escalation text so the eventual CLI boundary can print it verbatim.
 *
 * <p>Unchecked: a later task (7.9) maps this type to the internal-error exit code (1) via Spring
 * Boot's {@code ExitCodeExceptionMapper} (design D10); this task only defines the seam.
 *
 * <p>Implements FR9, D8 of add-manual-run.
 */
public final class InternalErrorException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message the rendered escalation text describing the internal error
     */
    public InternalErrorException(String message) {
        super(message);
    }
}
