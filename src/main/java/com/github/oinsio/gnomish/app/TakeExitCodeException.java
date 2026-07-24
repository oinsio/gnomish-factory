package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.take.TakeExitCodeMapper;
import com.github.oinsio.gnomish.app.take.TakeResult;
import java.io.Serial;

/**
 * Carries an already-computed process exit code out of a {@code take} run,
 * mirroring how {@link ManualRunRunner} lets exceptions propagate uncaught for
 * {@link RunExitCodeMapper} to catch. This is the mechanism task 5.13's
 * {@code TakeCommand} SHOULD use to terminate the process with the code
 * design D16 requires, without ever calling {@code System.exit} directly
 * (project convention: exception-based exit codes only):
 *
 * <ol>
 *   <li>compute the code via {@link TakeExitCodeMapper#exitCodeFor(TakeResult)}
 *       once the {@code take} run has a terminal {@link TakeResult};
 *   <li>throw {@code new TakeExitCodeException(code)};
 *   <li>let it propagate uncaught out of the command — Spring Boot's exit-code
 *       machinery takes it from there via {@link TakeExitCodeExceptionMapper}.
 * </ol>
 *
 * <p>The uncaught-exception case (design D16: "an uncaught exception runs the
 * abort protocol and exits 12 or 13, never a bare 1") is different control
 * flow, not this exception: task 5.13 catches the crash, runs the abort
 * protocol (task 5.3's {@link com.github.oinsio.gnomish.app.take.AbortHandler}),
 * obtains the resulting {@link TakeResult}, and only then goes through the
 * same three steps above.
 *
 * <p>Implements FR9, FR10, FR15, D16 of add-tracker-port.
 */
public final class TakeExitCodeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int exitCode;

    /**
     * @param exitCode the process exit code to terminate with, computed via {@link
     *     TakeExitCodeMapper#exitCodeFor(TakeResult)}
     */
    public TakeExitCodeException(int exitCode) {
        super("take exiting with code " + exitCode);
        this.exitCode = exitCode;
    }

    /** Returns the exit code {@link TakeExitCodeExceptionMapper} reports to Spring Boot. */
    public int exitCode() {
        return exitCode;
    }
}
