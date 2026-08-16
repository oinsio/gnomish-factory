package com.github.oinsio.gnomish.domain.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * The immutable per-round state of a task: its sealed {@link Position}, the
 * {@code attemptsUsed} count of quality-failure attempts burned in the current stage,
 * and the {@code attempts} history of every round executed within the current stage
 * (design D4). The persisted history is per-round; a fresh {@code TaskState} is produced
 * for every round, and the git history of persisted states is the long-term archive
 * (FR14).
 *
 * <p>{@code attemptsUsed} deliberately diverges from {@code attempts.size()}:
 * {@code attemptsUsed} counts only quality failures, while {@code attempts} records
 * <em>all</em> executed rounds — including {@code CannotVerify} and {@code DecisionNeeded}
 * rounds that burned tokens but no attempt — so cost analytics can see every round
 * (design D4, FR13).
 *
 * <p>Transition factories build a fresh state and never mutate {@code this}: each appends
 * to a copy of {@code attempts} and returns a new record. The engine assigns each
 * {@link AttemptRecord#round()} (0-based, matching {@link AttemptKey#attempt()}); these
 * factories append mechanically and neither interpret nor renumber rounds, so
 * {@code TaskState} stays pipeline-agnostic — it holds no knowledge of stage order.
 *
 * <p>Unlike {@code attempts} — which covers only the current stage and resets on
 * advancement — {@code totals} is a cumulative executor-usage aggregate for the whole
 * task: it is folded forward on every recorded round and carried across stage
 * advancement and resume, so it is the seam for future token budgets (FR13,
 * NFR-C1, design D5). It is a carried field, not derived from {@code attempts}, precisely
 * because a fold over the history would break the moment the history resets on advancement.
 *
 * <p>Implements FR8, FR13, FR14, NFR-C1 of add-stage-engine.
 *
 * @param position where the task sits in its pipeline; never null
 * @param attemptsUsed quality failures burned in the current stage; never negative
 * @param attempts every executed round of the current stage, in order; defensively
 *     copied, unmodifiable, possibly empty
 * @param totals cumulative executor usage over the whole task, folded forward on every
 *     recorded round and surviving advancement and resume; never null (FR13, NFR-C1, D5)
 */
public record TaskState(Position position, int attemptsUsed, List<AttemptRecord> attempts, ExecutorUsage totals) {

    public TaskState {
        attemptsUsed = requireNonNegative(attemptsUsed, "attemptsUsed");
        attempts = List.copyOf(attempts);
    }

    /**
     * The starting state for a stage: positioned at {@code stageName} with no attempts
     * burned and an empty history. A convenient entry point for the engine and tests
     * (FR8, FR14).
     *
     * @param stageName the stage to start at; never blank
     */
    public static TaskState atStageStart(String stageName) {
        return new TaskState(new Position.AtStage(stageName), 0, List.of(), ExecutorUsage.none());
    }

    /**
     * Returns a new state that appends {@code round} to the history without burning an
     * attempt and holds the current {@code position}. Used for rounds that record but do
     * not count against the limit: {@code CannotVerify} rounds, {@code DecisionNeeded}
     * rounds, and a passing round recorded before advancement (FR13).
     *
     * @param round the executed round to record; never null
     */
    public TaskState recordUnburnedRound(AttemptRecord round) {
        return new TaskState(position, attemptsUsed, append(round), totals.plus(round.executorUsage()));
    }

    /**
     * Returns a new state that appends {@code round} to the history and burns one attempt
     * ({@code attemptsUsed + 1}), holding the current {@code position}. Used for a quality
     * {@code Fail} round (FR13).
     *
     * @param round the failed round to record; never null
     */
    public TaskState recordQualityFailure(AttemptRecord round) {
        return new TaskState(position, attemptsUsed + 1, append(round), totals.plus(round.executorUsage()));
    }

    /**
     * Returns a new state at {@code next} with the history reset to empty and
     * {@code attemptsUsed} reset to zero, because the attempt history covers only the
     * current stage (FR14). The engine (task 5.8) decides the target using the pipeline —
     * {@code new Position.AtStage(nextName)} for a normal advance, or
     * {@code new Position.PipelineEnd()} when the last stage advances — so this factory
     * accepts any {@link Position} and holds no knowledge of stage order (design D4).
     *
     * <p>The cumulative {@code totals} are PRESERVED across the advance while the
     * current-stage history resets — the whole-task usage aggregate spans every stage
     * (FR13, NFR-C1, design D5).
     *
     * @param next the position to advance to; never null
     */
    public TaskState advanceTo(Position next) {
        return new TaskState(next, 0, List.of(), totals);
    }

    private List<AttemptRecord> append(AttemptRecord round) {
        var next = new ArrayList<AttemptRecord>(attempts);
        next.add(round);
        return next;
    }

    /**
     * Fails fast on a negative {@code attemptsUsed}: a burn count cannot be negative
     * (FR13). Kept as an explicit static method rather than inline in the compact
     * constructor: PIT's record filter suppresses all mutations inside a record's
     * canonical constructor, which would silently exempt this validation from the 100%
     * mutation gate.
     */
    private static int requireNonNegative(int value, String component) {
        if (value < 0) {
            throw new IllegalArgumentException("TaskState." + component + " must not be negative");
        }
        return value;
    }
}
