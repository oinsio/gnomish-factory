package com.github.oinsio.gnomish.app;

import java.io.Serial;

/**
 * A malformed or inconsistent {@code gnomish run} invocation: unknown/missing flag
 * combinations, an invalid {@code --task-id} charset, or (once the pipeline is loaded, task 7.3)
 * an unknown {@code --from-stage}. The message names exactly what is wrong and, per UX1, lists
 * the accepted values or forms — including the {@code --key=value} form design D5 mandates.
 *
 * <p>Unchecked: a later task (7.9) maps this type to the usage exit code (2) at the CLI
 * boundary; callers that only care about validity let it propagate rather than declaring it.
 *
 * <p>Implements FR1, FR12, UX1 of add-manual-run.
 */
public final class UsageException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message what is wrong and, where applicable, the accepted values/forms (UX1)
     */
    public UsageException(String message) {
        super(message);
    }
}
