package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The parsed flags of one {@code gnomish doctor} invocation, produced by {@link
 * DoctorArgumentsParser}. Public because the {@code doctor} command lives in its own package of
 * the composition root (design D3 of add-doctor-command).
 *
 * <p>Implements FR1, FR2, FR7 of add-doctor-command.
 *
 * @param dir the registered clone's path, absolute and normalized (FR7 of fix-operator-blockers),
 *     or {@code null} when the directory {@code doctor} runs in is registered by no project — doctor
 *     then runs its host-level checks only (FR2)
 * @param online whether {@code --online} was given (FR3)
 * @param json whether {@code --json} was given (FR5)
 */
public record DoctorArguments(@Nullable Path dir, boolean online, boolean json) {

    public DoctorArguments {
        if (dir != null) {
            ArgumentsParsingSupport.requireAbsoluteDir(dir);
        }
    }
}
