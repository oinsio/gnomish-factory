package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
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

    /**
     * The shared "no branch found" message both resume bootstraps ({@link
     * com.github.oinsio.gnomish.app.TakeResumeBootstrap} and {@link
     * com.github.oinsio.gnomish.app.TakeContainerResumeBootstrap}) raise once branch lookup for a
     * resumed {@code taskId} comes back empty, after hardening and (for host mode) a fetch
     * attempt.
     *
     * @param taskId the tracker's original taskId, as supplied to {@code take <ref>}
     * @return the exception, ready to throw
     */
    static UsageException branchNotFound(String taskId) {
        return new UsageException("could not resume task \"" + taskId + "\": no branch \""
                + TaskIdSanitizer.branchName(taskId)
                + "\" found locally, as a remote-tracking ref, or on origin (even after a fetch attempt)");
    }
}
