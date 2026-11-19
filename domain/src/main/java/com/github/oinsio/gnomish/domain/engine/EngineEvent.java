package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.PendingTurn;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The nine sealed events the engine emits synchronously as a run progresses — the
 * "Events at a glance" stream (design D7). Two run-level bookends,
 * {@link RunStarted} and {@link TaskFinished}, frame every run: they fire even for a
 * position already at {@code PipelineEnd} (an immediate completion) or a pre-flight
 * {@code PipelineMismatch}, so a run has no single {@code (stage, attempt)}. They
 * therefore carry {@code taskId} plus the richer payload the table fixes
 * ({@link Position}/{@link TaskOutcome}), which a bare {@link AttemptKey} — whose
 * {@code stage} is non-blank — cannot represent. The seven per-attempt/per-check
 * events carry the full {@code AttemptKey}; two of them, {@link CheckPolled} and {@link
 * CheckTimedOut}, report a pending check's wait (NFR-O1, UX1 of
 * migrate-verify-to-executor-contract), so the engine's observability of a wait crosses the
 * listener port like every other event rather than a logger of its own.
 *
 * <p>Every variant exposes {@link #taskId()} so a consumer can filter the whole
 * stream by task; key-carrying variants delegate to {@code key.taskId()} (UX2, the
 * key logs and telemetry share). The stream suffices to reconstruct a status view —
 * position, attempt, per-check results, aggregate metrics (NFR-O2). Inert values.
 *
 * <p>Implements FR12 of add-stage-engine; NFR-O1, UX1 of migrate-verify-to-executor-contract.
 */
public sealed interface EngineEvent
        permits EngineEvent.RunStarted,
                EngineEvent.AttemptStarted,
                EngineEvent.ExecutionFinished,
                EngineEvent.CheckStarted,
                EngineEvent.CheckPolled,
                EngineEvent.CheckTimedOut,
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
         * Fails fast on a blank {@code taskId}: a bookend that cannot name its task cannot
         * correlate its run (UX2). An explicit static method, not inline in the compact constructor:
         * PIT's record filter suppresses all mutations inside a record's canonical constructor.
         */
        private static String requireNonBlank(String value, String component) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("EngineEvent.RunStarted." + component + " must not be blank");
            }
            return value;
        }

        /** Fails fast on a negative burn count (FR12); explicit for {@link #requireNonBlank}'s PIT reason. */
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
     * An executor round finished — every executed round, recorded or not — carrying its usage, the
     * executor that ran it and the status it reached, which the round's log and ledger lines name.
     * The usage is the executor's {@code transform} participant entry, the same entry a recorded
     * round carries first in its usage list (FR15 of define-executor-contract).
     * Implements FR12 of add-stage-engine; NFR-O2, UX3, FR15 of define-executor-contract.
     *
     * @param key the {@code (taskId, stage, attempt)} correlation key; never null
     * @param usage the executor's participant entry for the round; never null
     * @param executor the executor that ran the round; never null
     * @param status the round's status; a thrown failure is {@code failed/infrastructure}; never null
     */
    record ExecutionFinished(
            AttemptKey key, ParticipantUsage usage, RoundExecutor executor, ExecutionResult.Status status)
            implements EngineEvent {
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
     * A pending check is about to be polled (NFR-O1 of migrate-verify-to-executor-contract): one
     * event per {@code poll} of its wait, emitted by {@link PendingPoll} before the poll is made.
     * The key is shown only as its prefix ({@link PendingKeyPrefix}), never whole: the event is
     * observability, and the whole key lives in the round's pending record.
     *
     * <p>Implements NFR-O1 of migrate-verify-to-executor-contract.
     *
     * @param key the {@code (taskId, stage, attempt)} correlation key; never null
     * @param check the identity of the polled check; never null
     * @param executor the executor name the check named; never blank
     * @param keyPrefix the shown prefix of the polled key, in the family of its source; never null
     * @param turn whether the key was this run's or read from the pending record; never null
     * @param elapsed the time since the entry's {@code since}; never null
     * @param remaining the time left to the wait's deadline; never null
     */
    record CheckPolled(
            AttemptKey key,
            CheckRef check,
            String executor,
            UntrustedText keyPrefix,
            PendingTurn.Origin turn,
            Duration elapsed,
            Duration remaining)
            implements EngineEvent {
        @Override
        public String taskId() {
            return key.taskId();
        }
    }

    /**
     * A pending check's wait reached its deadline (NFR-O1, UX1 of
     * migrate-verify-to-executor-contract), emitted by {@link PendingPoll} before the check's
     * {@link CheckFinished}: the failure the engine resolved the wait to, whose details for an
     * {@code scm-status} check name every expected context still absent.
     *
     * <p>Implements NFR-O1, UX1 of migrate-verify-to-executor-contract.
     *
     * @param key the {@code (taskId, stage, attempt)} correlation key; never null
     * @param check the identity of the timed-out check; never null
     * @param executor the executor name the check named; never blank
     * @param keyPrefix the shown prefix of the last key the wait polled; never null
     * @param elapsed the time since the entry's {@code since}; never null
     * @param failure the failure the engine resolved the wait to; never null
     */
    record CheckTimedOut(
            AttemptKey key,
            CheckRef check,
            String executor,
            UntrustedText keyPrefix,
            Duration elapsed,
            ExecutionResult.Failed failure)
            implements EngineEvent {
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
     * <p>Implements FR12 of add-stage-engine; NFR-O1, UX3 of migrate-verify-to-executor-contract.
     *
     * @param key the {@code (taskId, stage, attempt)} correlation key; never null
     * @param result the check's checkRef + verdict + duration; never null
     * @param outcome what an executor check's rounds came to; null for a judge check
     */
    record CheckFinished(
            AttemptKey key, CheckResult result, @Nullable CheckOutcome outcome) implements EngineEvent {
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
