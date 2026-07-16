package com.github.oinsio.gnomish.domain.engine;

/**
 * The seven sealed events the engine emits synchronously as a run progresses — the
 * "Events at a glance" stream (design D7). Two run-level bookends,
 * {@link RunStarted} and {@link TaskFinished}, frame every run: they fire even for a
 * position already at {@code PipelineEnd} (an immediate completion) or a pre-flight
 * {@code PipelineMismatch}, so a run has no single {@code (stage, attempt)}. They
 * therefore carry {@code taskId} plus the richer payload the table fixes
 * ({@link Position}/{@link TaskOutcome}), which a bare {@link AttemptKey} — whose
 * {@code stage} is non-blank — cannot represent. The five per-attempt/per-check
 * events carry the full {@code AttemptKey}.
 *
 * <p>Every variant exposes {@link #taskId()} so a consumer can filter the whole
 * stream by task; key-carrying variants delegate to {@code key.taskId()} (UX2, the
 * key logs and telemetry share). The stream suffices to reconstruct a status view —
 * position, attempt, per-check results, aggregate metrics (NFR-O2). Inert value
 * data compared by content.
 *
 * <p>Implements FR12 of add-stage-engine.
 */
public sealed interface EngineEvent
        permits EngineEvent.RunStarted,
                EngineEvent.AttemptStarted,
                EngineEvent.ExecutionFinished,
                EngineEvent.CheckStarted,
                EngineEvent.CheckFinished,
                EngineEvent.AttemptFinished,
                EngineEvent.TaskFinished {

    /**
     * The task this event belongs to, shared by every variant so a consumer can
     * filter the stream by task (UX2). Key-carrying variants delegate to
     * {@code key.taskId()}.
     *
     * <p>Implements FR12 of add-stage-engine.
     *
     * @return the run-correlating task id; never blank
     */
    String taskId();

    /**
     * A run started: the resolved {@link Position} and the {@code attemptsUsed} count
     * already burned in the current stage. A run-level bookend that fires for every
     * run, including a position at {@code PipelineEnd} or a pre-flight mismatch (D7),
     * so it carries {@code taskId} rather than an {@link AttemptKey}.
     *
     * <p>Implements FR12 of add-stage-engine.
     *
     * @param taskId the run-correlating task id; never blank
     * @param position the resolved pipeline position; never null
     * @param attemptsUsed attempts already burned in the current stage; never negative
     */
    record RunStarted(String taskId, Position position, int attemptsUsed) implements EngineEvent {

        public RunStarted {
            taskId = requireNonBlank(taskId, "taskId");
            attemptsUsed = requireNonNegative(attemptsUsed, "attemptsUsed");
        }

        /**
         * Fails fast on a blank {@code taskId}: a bookend that cannot name its task
         * cannot correlate its run (UX2). Kept as an explicit static method rather than
         * inline in the compact constructor: PIT's record filter suppresses all
         * mutations inside a record's canonical constructor, which would silently exempt
         * this validation from the 100% mutation gate.
         */
        private static String requireNonBlank(String value, String component) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("EngineEvent.RunStarted." + component + " must not be blank");
            }
            return value;
        }

        /**
         * Fails fast on a negative {@code attemptsUsed}: a burn count cannot be negative
         * (FR12). Explicit static method for the same PIT mutation-gate reason as
         * {@link #requireNonBlank}.
         */
        private static int requireNonNegative(int value, String component) {
            if (value < 0) {
                throw new IllegalArgumentException("EngineEvent.RunStarted." + component + " must not be negative");
            }
            return value;
        }
    }

    /**
     * An executor round is about to start. Carries only the {@link AttemptKey} — the
     * start of an attempt has no payload beyond the key (D7 table).
     *
     * <p>Implements FR12 of add-stage-engine.
     *
     * @param key the {@code (taskId, stage, attempt)} correlation key; never null
     */
    record AttemptStarted(AttemptKey key) implements EngineEvent {
        @Override
        public String taskId() {
            return key.taskId();
        }
    }

    /**
     * The executor round finished, carrying its {@link ExecutorUsage} telemetry so a
     * status view can accumulate aggregate metrics (NFR-O2).
     *
     * <p>Implements FR12 of add-stage-engine.
     *
     * @param key the {@code (taskId, stage, attempt)} correlation key; never null
     * @param usage the round's executor usage; never null
     */
    record ExecutionFinished(AttemptKey key, ExecutorUsage usage) implements EngineEvent {
        @Override
        public String taskId() {
            return key.taskId();
        }
    }

    /**
     * A verify check is about to run, named by its {@link CheckRef}.
     *
     * <p>Implements FR12 of add-stage-engine.
     *
     * @param key the {@code (taskId, stage, attempt)} correlation key; never null
     * @param check the identity of the check about to run; never null
     */
    record CheckStarted(AttemptKey key, CheckRef check) implements EngineEvent {
        @Override
        public String taskId() {
            return key.taskId();
        }
    }

    /**
     * A verify check finished, carrying its {@link CheckResult} — which already bundles
     * the {@code checkRef}, {@code verdict} and {@code duration} the D7 table names, so
     * the existing type is reused rather than repeating three loose fields.
     *
     * <p>Implements FR12 of add-stage-engine.
     *
     * @param key the {@code (taskId, stage, attempt)} correlation key; never null
     * @param result the check's checkRef + verdict + duration; never null
     */
    record CheckFinished(AttemptKey key, CheckResult result) implements EngineEvent {
        @Override
        public String taskId() {
            return key.taskId();
        }
    }

    /**
     * An attempt finished, carrying the new {@link TaskState} and the round's raw
     * {@link ToolTrace}. The "round result" the D7 table names is the last element of
     * {@code newState.attempts()} — it is not duplicated as a separate field.
     *
     * <p>Implements FR12 of add-stage-engine.
     *
     * @param key the {@code (taskId, stage, attempt)} correlation key; never null
     * @param newState the state after this attempt, its last {@code attempts} entry
     *     being the round result; never null
     * @param trace the round's raw tool trace; never null
     */
    record AttemptFinished(AttemptKey key, TaskState newState, ToolTrace trace) implements EngineEvent {
        @Override
        public String taskId() {
            return key.taskId();
        }
    }

    /**
     * The run terminated, carrying its terminal {@link TaskOutcome}. A run-level
     * bookend that fires for every run — including pre-flight escalations that never
     * reached a stage (D7) — so it carries {@code taskId} rather than an
     * {@link AttemptKey}.
     *
     * <p>Implements FR12 of add-stage-engine.
     *
     * @param taskId the run-correlating task id; never blank
     * @param outcome the terminal outcome of the run; never null
     */
    record TaskFinished(String taskId, TaskOutcome outcome) implements EngineEvent {

        public TaskFinished {
            taskId = requireNonBlank(taskId, "taskId");
        }

        /**
         * Fails fast on a blank {@code taskId}: a bookend that cannot name its task
         * cannot correlate its run (UX2). Explicit static method for the same PIT
         * mutation-gate reason as {@link RunStarted#requireNonBlank}.
         */
        private static String requireNonBlank(String value, String component) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("EngineEvent.TaskFinished." + component + " must not be blank");
            }
            return value;
        }
    }
}
