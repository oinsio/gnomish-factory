package com.github.oinsio.gnomish.domain.engine;

/**
 * The sealed location of a task within its pipeline: either positioned {@link AtStage}
 * a named stage, or parked at the explicit {@link PipelineEnd} (design D4). The two
 * variants let the engine switch exhaustively — {@code PipelineMismatch} applies only
 * to {@code AtStage} names, while a run at {@code PipelineEnd} returns {@code Completed}
 * immediately (FR8).
 *
 * <p>Modeling the end of the pipeline as an explicit value rather than a sentinel stage
 * name keeps position resolution honest when {@code .gnomish/} changes mid-task: a name
 * either resolves against the current pipeline or is a mismatch, and "done" is a
 * distinct state that never collides with any stage name (design D4).
 *
 * <p>Implements FR8 of add-stage-engine.
 */
public sealed interface Position permits Position.AtStage, Position.PipelineEnd {

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
            name = requireNonBlank(name, "name");
        }

        /**
         * Fails fast on a blank {@code name}: a stage position must name its stage
         * (FR8). Kept as an explicit static method rather than inline in the compact
         * constructor: PIT's record filter suppresses all mutations inside a record's
         * canonical constructor, which would silently exempt this validation from the
         * 100% mutation gate.
         */
        private static String requireNonBlank(String value, String component) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("Position.AtStage." + component + " must not be blank");
            }
            return value;
        }
    }

    /**
     * The explicit end of the pipeline: every stage is done, or a manual pause on the
     * last stage parked the task past it. A component-less marker — value-equal to any
     * other {@code PipelineEnd} — from which a subsequent run returns {@code Completed}
     * immediately (FR8).
     *
     * <p>Implements FR8 of add-stage-engine.
     */
    record PipelineEnd() implements Position {}
}
