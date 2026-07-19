package com.github.oinsio.gnomish.adapter.git;

import java.io.Serial;

/**
 * Thrown when {@link WorktreeSalvage#salvage} cannot durably commit an interrupted round's
 * uncommitted leftovers: {@code git add -A} or {@code git commit} exits non-zero. Mirrors {@link
 * GitPersistFailedException}'s "never swallow a failed commit" contract, scoped to the salvage
 * commit rather than a round commit.
 *
 * <p>Implements FR10 of add-git-workflow.
 */
public final class GitSalvageFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param taskId the task whose leftovers could not be salvaged
     * @param reason what failed, e.g. {@code "git add -A"} or {@code "git commit"}
     * @param detail the failing command's captured stderr; may be blank
     */
    public GitSalvageFailedException(String taskId, String reason, String detail) {
        super("failed to salvage uncommitted leftovers for taskId \"" + taskId + "\" (" + reason + "): " + detail);
    }
}
