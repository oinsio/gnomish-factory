package com.github.oinsio.gnomish.domain.engine;

/**
 * The terminal result the engine returns for one task run: either {@link Completed}
 * — the pipeline end was reached — {@link Paused} — a {@code manual} checkpoint —
 * {@link Escalated} — a human is needed, carrying an {@link EscalationReport} — or
 * {@link Aborted} — the durability guarantee broke. The four sealed variants let a
 * caller switch exhaustively while reading the shared final {@link TaskState}
 * without a switch.
 *
 * <p>An outcome is a value the caller acts on rather than a control-flow signal
 * (design D1): the engine returns escalation as data, and engine-internal errors
 * propagate as exceptions — never as outcomes. Every variant carries the final
 * {@code TaskState} through {@link #finalState()} so that a report is renderable
 * from the outcome and its final state alone (UX1): the current stage and the
 * recorded attempt history live in {@code finalState}, so no variant duplicates
 * them.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR10 of add-stage-engine.
 */
public sealed interface TaskOutcome
        permits TaskOutcome.Completed, TaskOutcome.Paused, TaskOutcome.Escalated, TaskOutcome.Aborted {

    /**
     * The final persisted state of the task at the moment the run terminated,
     * shared by every variant. A caller renders the outcome from this state alone —
     * the current stage and the full attempt history are here, not duplicated in
     * the variant (UX1).
     *
     * <p>Implements FR10 of add-stage-engine.
     *
     * @return the final task state; never null
     */
    TaskState finalState();

    /**
     * The task reached the pipeline end: every stage passed and no stage requested
     * a pause. Carries the final state — nothing more, since the outcome is simply
     * "done" (FR10).
     *
     * <p>Implements FR10 of add-stage-engine.
     *
     * @param finalState the final task state; never null
     */
    record Completed(TaskState finalState) implements TaskOutcome {}

    /**
     * A {@code manual} checkpoint paused the run: {@code passedStage} is the stage
     * that just passed verification and triggered the pause. Its position in
     * {@code finalState} is already advanced past it (FR8), so the pause names the
     * stage that passed rather than the one to run next. {@code passedStage} is
     * required non-blank — a pause with no stage identity cannot be resumed.
     *
     * <p>Implements FR10 of add-stage-engine.
     *
     * @param finalState the final task state; never null
     * @param passedStage the stage that passed and triggered the pause; never blank
     */
    record Paused(TaskState finalState, String passedStage) implements TaskOutcome {

        public Paused {
            passedStage = requireNonBlank(passedStage, "passedStage");
        }

        /**
         * Fails fast on a blank {@code passedStage}: a pause must name the stage that
         * passed so the run can resume from it (FR10). Kept as an explicit static
         * method rather than inline in the compact constructor: PIT's record filter
         * suppresses all mutations inside a record's canonical constructor, which
         * would silently exempt this validation from the 100% mutation gate.
         */
        private static String requireNonBlank(String value, String component) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("TaskOutcome.Paused." + component + " must not be blank");
            }
            return value;
        }
    }

    /**
     * A human is needed: the run escalated with a data-only {@link EscalationReport}
     * describing why (design D1). The report carries only the data not already in
     * {@code finalState}, so the pair renders a self-describing escalation (UX1).
     *
     * <p>Implements FR10 of add-stage-engine.
     *
     * @param finalState the final task state; never null
     * @param report why the run escalated; never null
     */
    record Escalated(TaskState finalState, EscalationReport report) implements TaskOutcome {}

    /**
     * A persistence failure broke the durability guarantee — the only outcome for a
     * failed {@code persist} (design D7). {@code failedAt} is the {@link AttemptKey}
     * of the round whose persist failed; {@code cause} is the failure detail with
     * the stack trace preserved (NFR-O1) and is required non-blank, since an abort
     * with no cause cannot be diagnosed. Unlike other outcomes, {@code finalState}
     * here is the last state the engine held in memory, which may not have reached
     * durable storage.
     *
     * <p>Implements FR10 of add-stage-engine.
     *
     * @param finalState the last in-memory task state; never null
     * @param failedAt the attempt key of the round whose persist failed; never null
     * @param cause the failure detail, stack trace preserved; never blank
     */
    record Aborted(TaskState finalState, AttemptKey failedAt, String cause) implements TaskOutcome {

        public Aborted {
            cause = requireNonBlank(cause, "cause");
        }

        /**
         * Fails fast on a blank {@code cause}: an abort must describe the persistence
         * failure that broke durability (NFR-O1). Kept as an explicit static method
         * rather than inline in the compact constructor for the same PIT
         * mutation-gate reason as {@link Paused}.
         */
        private static String requireNonBlank(String value, String component) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("TaskOutcome.Aborted." + component + " must not be blank");
            }
            return value;
        }
    }
}
