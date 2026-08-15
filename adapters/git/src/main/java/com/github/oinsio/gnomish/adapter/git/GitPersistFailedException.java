package com.github.oinsio.gnomish.adapter.git;

import java.io.Serial;

/**
 * Thrown when {@link GitAttemptPersistence} cannot durably commit a round: writing {@code
 * state.json} or {@code trace.jsonl} fails, {@code git add -A} exits non-zero, or {@code git
 * commit} exits non-zero. The engine's {@code AttemptPersistence} port is strict — a failed
 * persist must never return silently — so every such failure surfaces as this unchecked
 * exception, carrying the taskId, the attempt's stage/round, and (when available) the failing
 * git command's captured stderr for diagnosis.
 *
 * <p>Implements FR2, NFR-R1 of add-git-workflow.
 */
public final class GitPersistFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param taskId the task whose round could not be persisted
     * @param stage the attempt's stage
     * @param round the attempt's round number
     * @param reason what failed, e.g. {@code "git add -A"} or {@code "writing state.json"}
     * @param detail the failing command's captured stderr, or the underlying exception's
     *     message; may be blank
     */
    public GitPersistFailedException(String taskId, String stage, int round, String reason, String detail) {
        super("failed to persist round " + stage + "#" + round + " for taskId \"" + taskId + "\" (" + reason + "): "
                + detail);
    }

    /**
     * @param taskId the task whose round could not be persisted
     * @param stage the attempt's stage
     * @param round the attempt's round number
     * @param reason what failed
     * @param cause the underlying exception
     */
    public GitPersistFailedException(String taskId, String stage, int round, String reason, Throwable cause) {
        super(
                "failed to persist round " + stage + "#" + round + " for taskId \"" + taskId + "\" (" + reason + ")",
                cause);
    }
}
