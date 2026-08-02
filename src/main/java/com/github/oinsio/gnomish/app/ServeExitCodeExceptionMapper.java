package com.github.oinsio.gnomish.app;

import org.springframework.boot.ExitCodeExceptionMapper;
import org.springframework.stereotype.Component;

/**
 * Unwraps {@link ServeExitCodeException#exitCode()} for Spring Boot's exit-code machinery,
 * mirroring {@link TakeExitCodeExceptionMapper}. Coexists safely with {@link RunExitCodeMapper}
 * and {@link TakeExitCodeExceptionMapper} (Spring Boot's {@link ExitCodeExceptionMapper} contract:
 * for a thrown exception, Boot takes the highest code any registered mapper offers, falling back
 * to 1 if none claims it): each mapper only ever claims its own exception type, so there is no
 * overlap to reconcile.
 *
 * <p>Implements FR12, D7 of add-factory-serve.
 */
@Component
public final class ServeExitCodeExceptionMapper extends SingleExceptionExitCodeMapper<ServeExitCodeException> {

    public ServeExitCodeExceptionMapper() {
        super(ServeExitCodeException.class, ServeExitCodeException::exitCode);
    }
}
