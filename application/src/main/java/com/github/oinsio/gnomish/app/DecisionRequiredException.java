package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import java.io.Serial;

/**
 * A {@code gnomish run --resume} without {@code --decision} met a {@code DecisionNeeded} report
 * (FR4 of make-run-headless): the task stays parked exactly as recorded — no attempt burned, no
 * branch write — and the process exits 10 through {@link RunExitCodeMapper}. {@link
 * EscalationResume} has already restated the question with its return-path line on stdout by the
 * time this is thrown, so {@link RunExceptionReporting} prints nothing more and logs the return
 * path at INFO (NFR-O1) — the same shape {@link RunParkedException} takes for a fresh stop.
 *
 * <p>Implements FR4, NFR-O1 of make-run-headless.
 */
public final class DecisionRequiredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient TaskOutcome.Escalated outcome;
    private final transient TerminalOutcomeRender.ReturnPath returnPath;

    /**
     * @param outcome the recorded escalation the resume refused to continue without an answer
     * @param returnPath where the task resumes from, decision included
     */
    public DecisionRequiredException(TaskOutcome.Escalated outcome, TerminalOutcomeRender.ReturnPath returnPath) {
        super("run resume refused: the recorded escalation needs a decision");
        this.outcome = outcome;
        this.returnPath = returnPath;
    }

    /** The recorded escalation left parked; never null. */
    public TaskOutcome.Escalated outcome() {
        return outcome;
    }

    /**
     * The log record of the refusal: the task id and the resume command with its {@code
     * --decision} (NFR-O1 of make-run-headless).
     *
     * @return one line; never blank
     */
    public String stopRecord() {
        return "task " + returnPath.taskId() + " still needs a decision; " + returnPath.line(true);
    }
}
