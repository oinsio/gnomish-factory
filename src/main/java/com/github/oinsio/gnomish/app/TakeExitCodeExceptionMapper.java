package com.github.oinsio.gnomish.app;

import org.springframework.boot.ExitCodeExceptionMapper;
import org.springframework.stereotype.Component;

/**
 * Unwraps {@link TakeExitCodeException#exitCode()} for Spring Boot's exit-code
 * machinery. Coexists safely with {@link RunExitCodeMapper} (Spring Boot's {@link
 * ExitCodeExceptionMapper} contract: for a thrown exception, Boot takes the highest
 * code any registered mapper offers, falling back to 1 if none claims it): this mapper
 * only ever sees {@link TakeExitCodeException}, a type {@link RunExitCodeMapper} does
 * not claim, so there is no overlap to reconcile.
 *
 * <p>Implements FR9, FR10, FR15, D16 of add-tracker-port.
 */
@Component
public final class TakeExitCodeExceptionMapper implements ExitCodeExceptionMapper {

    /**
     * @param exception the uncaught exception the {@code take} command terminated with; never null
     * @return {@link TakeExitCodeException#exitCode()} when {@code exception} is a {@link
     *     TakeExitCodeException}, or 1 as the generic internal-error fallback for anything else —
     *     this mapper never claims another mapper's exception type with a different code
     */
    @Override
    public int getExitCode(Throwable exception) {
        return exception instanceof TakeExitCodeException e ? e.exitCode() : 1;
    }
}
