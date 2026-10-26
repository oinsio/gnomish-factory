package com.github.oinsio.gnomish.subprocess;

import java.nio.file.Path;

/**
 * What a command's first word resolves to under the launch owner's rule ({@code ExecutableLookup},
 * design D19 as amended 2026-10-17 of fix-terminal-receipt-convergence): the absolute executable,
 * its {@code #!} interpreter chain judged, or the reason it does not resolve. The verdict is read
 * without starting a process ({@link ProcessLaunch#assessExecutable}); the launch refuses exactly
 * when it is {@link Unresolved}, with the {@link Unresolved#reason()} as its exception's message
 * (design D1, D4 of add-doctor-command).
 *
 * <p>Implements FR1, FR4 of add-doctor-command.
 */
public sealed interface ExecutableVerdict {

    /**
     * The name resolves.
     *
     * @param executable the absolute executable the launcher would run
     */
    record Resolved(Path executable) implements ExecutableVerdict {}

    /**
     * The name does not resolve, and the launch would start nothing.
     *
     * @param reason the launch's refusal, word for word, naming the command
     */
    record Unresolved(String reason) implements ExecutableVerdict {}
}
