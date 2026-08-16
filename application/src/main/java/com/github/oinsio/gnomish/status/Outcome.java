package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;

/**
 * The terminal result of a task run, mirroring {@link TaskOutcome}'s four sealed
 * variants as a report-model shape (JSON contract's {@code outcome}): {@link
 * Completed}, {@link Paused}, {@link Escalated}, {@link Aborted}. Unlike {@code
 * TaskOutcome}, no variant carries a {@code finalState} — the report already
 * exposes the final {@code TaskState} through {@link StatusReport}'s own
 * state-derivable fields, so repeating it here would duplicate data the contract
 * gets elsewhere.
 *
 * <p>{@code null} at {@link StatusReport#outcome()} means the run has not yet
 * terminated (still mid-run); this differs from {@link LiveActivity#activity()}
 * being {@code null}, which means idle-but-still-running.
 *
 * <p>Live-only: populated by {@link StatusEventListener} from {@code
 * EngineEvent.TaskFinished} for all four {@link TaskOutcome} kinds, so it does not
 * survive into a persisted {@code TaskState} the way {@code lastEscalation} does
 * not (design D7).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR11, D7 of add-manual-run.
 */
public sealed interface Outcome permits Outcome.Completed, Outcome.Paused, Outcome.Escalated, Outcome.Aborted {

    /**
     * The task reached the pipeline end: every stage passed and no stage requested
     * a pause. Mirrors {@link TaskOutcome.Completed}.
     *
     * <p>Implements FR11, D7 of add-manual-run.
     */
    record Completed() implements Outcome {}

    /**
     * A {@code manual} checkpoint paused the run, naming the stage that just
     * passed verification. Mirrors {@link TaskOutcome.Paused}.
     *
     * <p>Implements FR11, D7 of add-manual-run.
     *
     * @param passedStage the stage that passed and triggered the pause; never blank
     */
    record Paused(String passedStage) implements Outcome {}

    /**
     * A human is needed: the run escalated with a data-only {@link
     * EscalationReport}. Mirrors {@link TaskOutcome.Escalated}.
     *
     * <p>Implements FR11, D7 of add-manual-run.
     *
     * @param report why the run escalated; never null
     */
    record Escalated(EscalationReport report) implements Outcome {}

    /**
     * A persistence failure broke the durability guarantee. Mirrors {@link
     * TaskOutcome.Aborted}.
     *
     * <p>Implements FR11, D7 of add-manual-run.
     *
     * @param failedAt the attempt key of the round whose persist failed; never null
     * @param cause the failure detail, stack trace preserved; never blank
     */
    record Aborted(AttemptKey failedAt, String cause) implements Outcome {}

    /**
     * Derives the report-model {@code Outcome} from the engine's {@link
     * TaskOutcome}, dropping the {@code finalState} each variant carries (already
     * available elsewhere on the report) by an exhaustive switch over the sealed
     * variants — no {@code default} arm, so a new {@code TaskOutcome} variant fails
     * to compile here until its mapping is added.
     *
     * <p>Implements FR11, D7 of add-manual-run.
     *
     * @param outcome the engine's terminal outcome; never null
     * @return the equivalent report-model outcome
     */
    static Outcome from(TaskOutcome outcome) {
        return switch (outcome) {
            case TaskOutcome.Completed ignored -> new Completed();
            case TaskOutcome.Paused paused -> new Paused(paused.passedStage());
            case TaskOutcome.Escalated escalated -> new Escalated(escalated.report());
            case TaskOutcome.Aborted aborted -> new Aborted(aborted.failedAt(), aborted.cause());
        };
    }
}
