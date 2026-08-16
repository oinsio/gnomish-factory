package com.github.oinsio.gnomish.app;

import java.io.Serial;

/**
 * Carries an already-computed process exit code out of a {@code gnomish serve} invocation,
 * mirroring {@link TakeExitCodeException} — never a direct {@code System.exit} call (project
 * convention). Task 5.1's only use is exit code 1, thrown when the startup label-provisioning
 * smoke test (FR12, design D7: "an unreachable repository is death on startup") cannot reach the
 * configured tracker binding, before anything has been claimed. Exit code 0 (a clean {@code
 * --drain}/{@code SIGTERM} stop) and 2 (usage errors, already covered by the existing {@link
 * UsageException} → {@link RunExitCodeMapper} path) are out of this exception's scope.
 *
 * <p>Implements FR12, D7 of add-factory-serve.
 */
public final class ServeExitCodeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int exitCode;

    /** @param exitCode the process exit code to terminate with */
    public ServeExitCodeException(int exitCode) {
        super("serve exiting with code " + exitCode);
        this.exitCode = exitCode;
    }

    /** Returns the exit code {@link ServeExitCodeExceptionMapper} reports to Spring Boot. */
    public int exitCode() {
        return exitCode;
    }
}
