package com.github.oinsio.gnomish.adapter.git;

import java.io.Serial;

/**
 * Thrown when a taskId cannot be sanitized into a safe branch/directory name segment:
 * sanitization ({@link TaskIdSanitizer}) produced an empty result, or a result ending in
 * {@code .lock} (git's own lock-file suffix, unsafe as a ref or worktree directory name). The
 * message carries the offending, un-sanitized taskId for diagnosis.
 *
 * <p>Unchecked: an invalid taskId is a caller-input error the task-creation boundary surfaces
 * directly, not a condition recoverable mid-sanitization (same idiom as {@link
 * com.github.oinsio.gnomish.adapter.git.state.UnsupportedStateFileVersionException}).
 *
 * <p>Implements FR2, FR7 of add-git-workflow (design D16).
 */
public final class InvalidTaskIdException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String taskId;

    /**
     * @param taskId the offending, un-sanitized taskId; never blank
     * @param reason why sanitization rejected it, e.g. {@code "sanitizes to an empty string"}
     */
    public InvalidTaskIdException(String taskId, String reason) {
        super("invalid taskId \"" + taskId + "\": " + reason);
        this.taskId = taskId;
    }

    /**
     * Returns the offending, un-sanitized taskId.
     *
     * @return the offending taskId
     */
    public String taskId() {
        return taskId;
    }
}
