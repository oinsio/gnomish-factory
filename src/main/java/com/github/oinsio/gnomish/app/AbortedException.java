package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * Signals that {@link RunnerOutcomeLoop#handleAborted} finished reporting a broken
 * durability guarantee: the cause and unpersisted-state summary are already printed to
 * {@link System#err} by the time this is thrown. Without this exception, {@code
 * Aborted} was indistinguishable from {@code Completed} at the CLI boundary — both
 * returned from the loop normally. {@link RunExitCodeMapper} maps this to the {@code
 * Aborted}-family exit code (12).
 *
 * <p>{@link #outcome()} carries the full {@link TaskOutcome.Aborted} when the thrower has one
 * in hand (git mode, task 4.7 of add-git-workflow: {@link GitModeRunner}/{@link GitResumeRunner}
 * need the structured outcome — not just its rendered cause string — to record it through {@link
 * com.github.oinsio.gnomish.app.port.TaskRepository}); it is {@code null} for the plain-message
 * constructor add-manual-run's {@link RunnerOutcomeLoop} already used before this field existed.
 *
 * <p>Implements FR12, D10 of add-manual-run; FR1, FR6, FR8 of add-git-workflow.
 */
public final class AbortedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient TaskOutcome.@Nullable Aborted outcome;

    /**
     * @param cause the already-printed abort cause, carried for diagnostics
     */
    public AbortedException(String cause) {
        super(cause);
        this.outcome = null;
    }

    /**
     * @param outcome the full {@code Aborted} outcome a git-mode caller can record through {@link
     *     com.github.oinsio.gnomish.app.port.TaskRepository} (FR1, FR6, FR8 of add-git-workflow)
     */
    public AbortedException(TaskOutcome.Aborted outcome) {
        super(outcome.cause());
        this.outcome = outcome;
    }

    /**
     * Returns the full {@code Aborted} outcome, or {@code null} when this was constructed from a
     * bare cause string only.
     */
    public TaskOutcome.@Nullable Aborted outcome() {
        return outcome;
    }
}
