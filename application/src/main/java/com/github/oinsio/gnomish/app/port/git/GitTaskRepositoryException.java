package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.Serial;

/**
 * Thrown when {@code GitTaskRepository} (the {@code :adapters:git} implementation) cannot
 * durably record a task-lifecycle write: the branch/worktree for a taskId cannot be located or
 * created, {@code task.json} cannot be written or read, or the {@code git add}/{@code commit}
 * step fails. {@link TaskRepository} (app/port) is documented as a strict port — a failed
 * lifecycle write must never return silently — so every such failure surfaces as this unchecked
 * exception, carrying the taskId, the lifecycle event in play, and (when available) the failing
 * step's detail for diagnosis.
 *
 * <p>Kept separate from {@code GitPersistFailedException} (also {@code :adapters:git}): that
 * type's fields (stage, round) describe a round-scoped {@code AttemptPersistence} failure, a
 * different seam (design D1) from this port's task-scoped lifecycle events.
 *
 * <p>Implements FR1 of add-git-workflow.
 */
public final class GitTaskRepositoryException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The failing write, with its detail as a carrier — a subprocess's own words
     * ({@code SUBPROCESS}) or the factory's own sentence about what it could not find
     * ({@code FACTORY}, e.g. "no branch found to reconcile a deferred finish from"). Untrusted
     * text leaves its carrier through the log exit here, at the throw, because an exception
     * message escapes every log-call gate structurally (design D5 of type-untrusted-text).
     *
     * <p>There is deliberately no {@code String} arm beside this one: a same-arity overload
     * taking the raw form would let a caller hand over captured text unwrapped and still
     * compile, which is the escape hatch {@code implementation.md} item 3 forbids. Factory prose
     * is a family of its own ({@code UntrustedText.factory}), so it needs no second arm.
     *
     * @param taskId the task whose lifecycle event could not be recorded
     * @param event the lifecycle write that failed
     * @param reason what failed, e.g. {@code "git add -A"} or {@code "writing task.json"}
     * @param detail the failing command's captured stderr, or the factory's own explanation
     */
    public GitTaskRepositoryException(String taskId, TaskLifecycleEvent event, String reason, UntrustedText detail) {
        super("failed to record task " + event + " for taskId \"" + taskId + "\" (" + reason + "): " + detail.forLog());
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
