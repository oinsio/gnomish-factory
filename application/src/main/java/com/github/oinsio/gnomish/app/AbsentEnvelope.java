package com.github.oinsio.gnomish.app;

import java.nio.file.Path;

/**
 * The failure the host-mode envelope reads report where absence cannot happen (design D2 of
 * fix-envelope-medium): the run itself committed the envelope moments earlier, so an empty read at
 * the branch tip is an invariant violation, not a route.
 *
 * <p>{@link InternalErrorException} is the type the application already uses for an impossible
 * recorded state, so these paths raise it rather than growing a port-level exception whose only
 * throw sites would be these lambdas. The two legal absences — a delivered branch whose cleanup
 * commit removed the envelope, and a pre-contract tip carrying no state — are routed on the empty
 * {@code Optional} in {@link HostResumeMechanics} instead.
 *
 * <p>Implements FR1 of fix-envelope-medium.
 */
final class AbsentEnvelope {

    private AbsentEnvelope() {}

    /** The task envelope, absent at the tip a moment after this run committed it. */
    static InternalErrorException task(String taskId, Path worktree) {
        return at(taskId, "the task envelope", worktree);
    }

    /** The state envelope, absent at the tip a moment after this run committed it. */
    static InternalErrorException state(String taskId, Path worktree) {
        return at(taskId, "the state envelope", worktree);
    }

    private static InternalErrorException at(String taskId, String envelope, Path worktree) {
        return new InternalErrorException("task \"%s\": %s is absent at HEAD of %s although this run committed it"
                .formatted(taskId, envelope, worktree));
    }
}
