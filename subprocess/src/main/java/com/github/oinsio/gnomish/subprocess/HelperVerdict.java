package com.github.oinsio.gnomish.subprocess;

/**
 * Whether the host has the helpers every host launch needs (design D19 of
 * fix-terminal-receipt-convergence): the platform's process-group launcher and the group sweep's
 * {@code kill}. The verdict is the launch owner's own decision, read without starting a process
 * ({@link ProcessLaunch#assessHelper()}); the launch refuses exactly when it is {@link Missing},
 * with the {@link Missing#reason()} as its exception's message (design D1, D4 of add-doctor-command).
 *
 * <p>Implements FR1, FR4 of add-doctor-command.
 */
public sealed interface HelperVerdict {

    /**
     * Both helpers are present.
     *
     * @param launcher the process-group launcher the launch puts in front of a command
     * @param kill the group sweep's signaller
     */
    record Present(String launcher, String kill) implements HelperVerdict {}

    /**
     * A helper is missing, and no host process would be started.
     *
     * @param tool the missing tool, or the platform that has none
     * @param reason the launch's refusal, word for word
     * @param install what provides the tool: the package to install, or why there is none
     */
    record Missing(String tool, String reason, String install) implements HelperVerdict {}
}
