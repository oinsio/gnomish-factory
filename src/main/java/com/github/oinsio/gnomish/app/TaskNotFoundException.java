package com.github.oinsio.gnomish.app;

import java.io.Serial;

/**
 * Signals that {@code status}/{@code usage} found no {@code gnomish/<task>} branch anywhere —
 * locally, as a remote-tracking ref, or on {@code origin} after the narrow-fetch attempt (design
 * D13, {@link com.github.oinsio.gnomish.adapter.git.BranchLocation.NotFound}). Per design D15 and
 * UX3, branch death after a merged PR is the expected end state of a task, not a tool failure: the
 * calm "task not found" line is printed to {@link System#out} by the throwing command before this
 * type is thrown, so {@link ManualRunRunner} rethrows it unadorned — no {@code System.err} line, no
 * WARN log, no stack trace — and {@link RunExitCodeMapper} settles it on its own exit code (6),
 * distinct from both a clean report (0) and the generic internal-error fallback (1), so scripts can
 * tell "nothing to report" apart from "the tool broke."
 *
 * <p>Implements FR13, UX3 of add-git-workflow.
 */
public final class TaskNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param taskId the task id no branch was found for; never blank
     */
    public TaskNotFoundException(String taskId) {
        super("task not found: " + taskId);
    }
}
