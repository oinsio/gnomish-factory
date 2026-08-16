package com.github.oinsio.gnomish.app;

import java.util.function.ToIntFunction;
import org.springframework.boot.ExitCodeExceptionMapper;

/**
 * Shared {@link ExitCodeExceptionMapper} shape for mappers that claim exactly one exception
 * type and unwrap an already-computed exit code from it, falling back to 1 for anything else.
 * Extracted from the identical {@code getExitCode} bodies of {@link
 * ServeExitCodeExceptionMapper} and {@link TakeExitCodeExceptionMapper}.
 *
 * <p>Implements the exit-code-unwrap contract behind {@link ServeExitCodeExceptionMapper}
 * (FR12, D7 of add-factory-serve) and {@link TakeExitCodeExceptionMapper} (FR9, FR10, FR15,
 * D16 of add-tracker-port).
 */
abstract class SingleExceptionExitCodeMapper<T extends Throwable> implements ExitCodeExceptionMapper {

    private final Class<T> exceptionType;
    private final ToIntFunction<T> exitCodeOf;

    /**
     * @param exceptionType the only exception type this mapper claims
     * @param exitCodeOf extracts the carried exit code from an instance of {@code exceptionType}
     */
    protected SingleExceptionExitCodeMapper(Class<T> exceptionType, ToIntFunction<T> exitCodeOf) {
        this.exceptionType = exceptionType;
        this.exitCodeOf = exitCodeOf;
    }

    /**
     * @param exception the uncaught exception the command terminated with; never null
     * @return the exit code carried by {@code exception} when it is an instance of the claimed
     *     type, or 1 as the generic internal-error fallback for anything else
     */
    @Override
    public final int getExitCode(Throwable exception) {
        return exceptionType.isInstance(exception) ? exitCodeOf.applyAsInt(exceptionType.cast(exception)) : 1;
    }
}
