package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * Carries a {@code gnomish run} stop out of the process (design D1 of make-run-headless): the
 * terminal boundary records the park, then throws this so Spring Boot's exit-code machinery — which
 * reads only exceptions — can turn the outcome into exit 10 ({@code Escalated}) or 11 ({@code
 * Paused}) through {@link RunExitCodeMapper}. The {@code run} twin of {@link TakeExitCodeException}.
 * The stop render is already on stdout by the time this is thrown, so {@link RunExceptionReporting}
 * prints nothing more and logs the return path at INFO (NFR-O1).
 *
 * <p>Implements FR1, FR2, FR7, NFR-O1 of make-run-headless.
 */
public final class RunParkedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient TaskOutcome outcome;
    private final transient TerminalOutcomeRender.@Nullable ReturnPath returnPath;

    /**
     * @param outcome the stop the run ended on: {@code Escalated} or {@code Paused}
     * @param returnPath where the task resumes from; {@code null} in in-place mode
     * @throws IllegalArgumentException if {@code outcome} is not a stop
     */
    public RunParkedException(TaskOutcome outcome, TerminalOutcomeRender.@Nullable ReturnPath returnPath) {
        super("run stopped: " + kind(outcome));
        this.outcome = outcome;
        this.returnPath = returnPath;
    }

    /** The stop the run ended on: {@code Escalated} or {@code Paused}; never null. */
    public TaskOutcome outcome() {
        return outcome;
    }

    /** Whether the stop is a manual checkpoint (exit 11) rather than an escalation (exit 10). */
    public boolean checkpoint() {
        return outcome instanceof TaskOutcome.Paused;
    }

    /**
     * The log record of the stop: the task id and the resume command, or — in in-place mode — that
     * nothing can be resumed (NFR-O1 of make-run-headless).
     *
     * @return one line; never blank
     */
    public String stopRecord() {
        if (returnPath == null) {
            return "run stopped " + kind(outcome) + " in in-place mode; there is no branch to resume from";
        }
        return "task " + returnPath.taskId() + " stopped " + kind(outcome) + "; " + returnPath.line(!checkpoint());
    }

    private static String kind(TaskOutcome outcome) {
        return switch (outcome) {
            case TaskOutcome.Escalated ignored -> "escalated";
            case TaskOutcome.Paused ignored -> "paused";
            case TaskOutcome.Completed ignored -> throw notAStop(outcome);
            case TaskOutcome.Aborted ignored -> throw notAStop(outcome);
        };
    }

    private static IllegalArgumentException notAStop(TaskOutcome outcome) {
        return new IllegalArgumentException("RunParkedException carries an Escalated or Paused outcome, not "
                + outcome.getClass().getSimpleName());
    }
}
