package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.branch.BranchShapeDiagnosis;
import com.github.oinsio.gnomish.domain.branch.BranchShape;
import java.io.Serial;

/**
 * Signals that {@code status} found the task branch but its shape refuses inspection — {@code
 * Corrupt}, {@code UnsupportedVersion} or {@code Unknown}, the three shapes whose recovery
 * disposition is quarantine (FR16, UX4 of harden-task-branch-contract). Mirrors {@link
 * TaskNotFoundException}'s protocol: the calm diagnosis naming the offending file and the observed
 * versus expected content is printed to {@link System#out} by {@link StatusCommand} before this
 * type is thrown, so it is rethrown unadorned — no stack trace, no WARN log — and {@link
 * RunExitCodeMapper} settles it on its own exit code (7), distinct from a clean report (0), from
 * "no such task" (6), and from the generic internal-error fallback (1). Nothing is mutated: the
 * reader that classified the branch never wrote to it.
 *
 * <p>Implements FR16, UX4 of harden-task-branch-contract.
 */
public final class BranchShapeRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param taskId the task whose branch refused inspection; never blank
     * @param shape the quarantine shape it classified as; never null
     */
    public BranchShapeRefusedException(String taskId, BranchShape shape) {
        super("task " + taskId + " classifies as " + BranchShapeDiagnosis.phrase(shape));
    }
}
