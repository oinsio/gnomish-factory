package com.github.oinsio.gnomish.adapter.git;

import java.io.Serial;

/**
 * Thrown when {@link GitTaskRepository} cannot durably record a task-lifecycle write: the
 * branch/worktree for a taskId cannot be located or created, {@code task.json} cannot be
 * written or read, or the {@code git add}/{@code commit} step fails. {@link TaskRepository}
 * (app/port) is documented as a strict port — a failed lifecycle write must never return
 * silently — so every such failure surfaces as this unchecked exception, carrying the taskId,
 * the lifecycle event in play, and (when available) the failing step's detail for diagnosis.
 *
 * <p>Kept separate from {@link GitPersistFailedException}: that type's fields (stage, round)
 * describe a round-scoped {@code AttemptPersistence} failure, a different seam (design D1) from
 * this port's task-scoped lifecycle events.
 *
 * <p>Implements FR1 of add-git-workflow.
 */
public final class GitTaskRepositoryException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param taskId the task whose lifecycle event could not be recorded
     * @param event the lifecycle write that failed
     * @param reason what failed, e.g. {@code "git add -A"} or {@code "writing task.json"}
     * @param detail the failing command's captured stderr, or the underlying exception's
     *     message; may be blank
     */
    public GitTaskRepositoryException(String taskId, TaskLifecycleEvent event, String reason, String detail) {
        super("failed to record task " + event + " for taskId \"" + taskId + "\" (" + reason + "): " + detail);
    }

    /**
     * @param taskId the task whose lifecycle event could not be recorded
     * @param event the lifecycle write that failed
     * @param reason what failed
     * @param cause the underlying exception
     */
    public GitTaskRepositoryException(String taskId, TaskLifecycleEvent event, String reason, Throwable cause) {
        super("failed to record task " + event + " for taskId \"" + taskId + "\" (" + reason + ")", cause);
    }
}
