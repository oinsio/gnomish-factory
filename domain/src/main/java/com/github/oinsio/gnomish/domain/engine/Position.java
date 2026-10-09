package com.github.oinsio.gnomish.domain.engine;

/**
 * The sealed location of a task within its pipeline: positioned {@link AtStage} a named
 * stage, held {@link AwaitingApproval} at the gate of a {@code manual} stage that passed,
 * or parked at the explicit {@link PipelineEnd} (design D4). The variants let every reader
 * switch exhaustively — {@code PipelineMismatch} applies only to {@code AtStage} names, a
 * run at {@code PipelineEnd} returns {@code Completed} immediately (FR8), and a run at a
 * gate returns {@code Paused} (FR2 of make-checkpoint-gate-durable).
 *
 * <p>Modeling the end of the pipeline as an explicit value rather than a sentinel stage
 * name keeps position resolution honest when {@code .gnomish/} changes mid-task: a name
 * either resolves against the current pipeline or is a mismatch, and "done" is a
 * distinct state that never collides with any stage name (design D4).
 *
 * <p>Implements FR8 of add-stage-engine; FR1 of make-checkpoint-gate-durable.
 */
public sealed interface Position permits Position.AtStage, Position.AwaitingApproval, Position.PipelineEnd {

    /**
     * The task is positioned at the stage named {@code name}. The name is the key the
     * engine resolves against the current pipeline; a name absent from the pipeline is a
     * {@code PipelineMismatch}, not an error here (FR8).
     *
     * <p>Implements FR8 of add-stage-engine.
     *
     * @param name the stage name the task is positioned at; never blank
     */
    record AtStage(String name) implements Position {

        public AtStage {
            name = requireNonBlank(name, "AtStage.name");
        }
    }

    /**
     * The task is held at the gate of the {@code manual} stage named {@code stage}: that
     * stage passed, and the position moves past it only through the approval write (design
     * D1, D2 of make-checkpoint-gate-durable). The position <em>is</em> the gate — a run
     * starting here pauses again, however many times it is picked up, because the stop is a
     * recorded fact rather than something re-derived from the pipeline.
     *
     * <p>Implements FR1, FR2 of make-checkpoint-gate-durable.
     *
     * @param stage the name of the {@code manual} stage that passed; never blank
     */
    record AwaitingApproval(String stage) implements Position {

        public AwaitingApproval {
            stage = requireNonBlank(stage, "AwaitingApproval.stage");
        }
    }

    /**
     * The explicit end of the pipeline: every stage is done, or the approval of a gate on
     * the last stage moved the task past it. A component-less marker — value-equal to any
     * other {@code PipelineEnd} — from which a subsequent run returns {@code Completed}
     * immediately (FR8).
     *
     * <p>Implements FR8 of add-stage-engine.
     */
    record PipelineEnd() implements Position {}

    /**
     * Fails fast on a blank stage name: a position that names a stage — {@link AtStage} or
     * {@link AwaitingApproval} — must name it (FR8; FR1 of make-checkpoint-gate-durable). Kept
     * as an explicit static method rather than inline in the records' compact constructors:
     * PIT's record filter suppresses all mutations inside a record's canonical constructor,
     * which would silently exempt this validation from the 100% mutation gate.
     */
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Position." + component + " must not be blank");
        }
        return value;
    }
}
