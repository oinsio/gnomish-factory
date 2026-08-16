package com.github.oinsio.gnomish.domain.engine;

/**
 * The {@code (taskId, stage, attempt)} correlation key that ties together every
 * per-attempt artifact of the engine: the raw {@link ToolTrace} header, the log
 * lines the operator reads, and (downstream) the seven engine events. Modelled
 * as one shared, reusable value so logs and telemetry correlate on the exact
 * same key (UX2) and the raw trace kept outside {@code TaskState} can be matched
 * back to its attempt (design D5).
 *
 * <p>The {@code taskId} and {@code stage} are non-blank because a key that does
 * not name its task and stage cannot correlate anything. The {@code attempt} is
 * non-negative; it corresponds to {@code AttemptRecord.round}, the monotonic
 * executed-round number the engine assigns within the current stage — monotonicity
 * is the engine's concern and is not validated here. Inert value data compared by
 * content.
 *
 * <p>Implements FR13 of add-stage-engine.
 *
 * @param taskId the opaque task identifier; matches {@code TaskContext.taskId};
 *     never blank
 * @param stage the stage name the attempt belongs to; never blank
 * @param attempt the round sequence number within the stage; corresponds to
 *     {@code AttemptRecord.round}; never negative
 */
public record AttemptKey(String taskId, String stage, int attempt) {

    public AttemptKey {
        taskId = requireNonBlank(taskId, "taskId");
        stage = requireNonBlank(stage, "stage");
        attempt = requireNonNegative(attempt, "attempt");
    }

    /**
     * Fails fast on a blank component: a correlation key must name its task and
     * stage (FR13). Kept as an explicit static method rather than inline in the
     * compact constructor: PIT's record filter suppresses all mutations inside a
     * record's canonical constructor, which would silently exempt this validation
     * from the 100% mutation gate.
     */
    private static String requireNonBlank(String value, String component) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("AttemptKey." + component + " must not be blank");
        }
        return value;
    }

    /**
     * Fails fast on a negative {@code attempt}: a round number cannot be negative
     * (FR13). Explicit static method for the same PIT mutation-gate reason as
     * {@link #requireNonBlank}.
     */
    private static int requireNonNegative(int value, String component) {
        if (value < 0) {
            throw new IllegalArgumentException("AttemptKey." + component + " must not be negative");
        }
        return value;
    }
}
