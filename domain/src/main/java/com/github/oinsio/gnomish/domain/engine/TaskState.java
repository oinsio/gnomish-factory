package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * The immutable per-round state of a task: its sealed {@link Position}, the {@code attemptsUsed}
 * quality failures burned in the current stage, and the {@code attempts} history of every round of
 * the current stage, of which {@code attemptsUsed} counts only the quality failures (design D4,
 * FR13). A fresh state is produced for every round; git history keeps the archive (FR14).
 *
 * <p>Transitions build a fresh state and never mutate {@code this}; they append rounds mechanically
 * and neither interpret nor renumber them ({@link #approveGate} asks {@link Advancement}).
 *
 * <p>{@code totals} is the whole task's {@link UsageSnapshot}: every recorded round's participant
 * entries folded through {@link UsageFold}, the one fold, by the round writers, and carried unchanged
 * by every lifecycle rewrite across advancement, decision reset and resume — a carried field, because
 * a fold over {@code attempts} would break when the history resets (FR13, NFR-C1, design D5; FR17 of
 * define-executor-contract, design D15). A round with no participant entry is refused by the fold.
 *
 * <p>{@code executorVersions} is the task's executor version pin, carried the same way: per executor
 * name, the describe version of the first round that ran it, written by the round writers in that
 * round's own commit and carried unchanged by every lifecycle rewrite (design D11 of
 * define-executor-contract). The carry is structural: every rewrite goes through {@link #rewrite},
 * every round writer through {@link #recorded}, so no transition can drop either carried field.
 *
 * <p>Implements FR8, FR13, FR14, NFR-C1 of add-stage-engine; FR13, FR17 of define-executor-contract.
 *
 * @param position     where the task sits in its pipeline; never null
 * @param attemptsUsed quality failures burned in the current stage; never negative
 * @param attempts     every executed round of the current stage, in order; defensively
 *                     copied, unmodifiable, possibly empty
 * @param totals       the usage snapshot of the whole task, folded forward on every recorded round
 *                     and surviving advancement and resume; never null (FR13, NFR-C1, D5; FR17 of
 *                     define-executor-contract)
 * @param executorVersions the executor version pin: the first recorded describe version per executor
 *                     name, surviving advancement, decision reset and resume; never null (FR13 of
 *                     define-executor-contract)
 */
public record TaskState(
        Position position,
        int attemptsUsed,
        List<AttemptRecord> attempts,
        UsageSnapshot totals,
        ExecutorVersions executorVersions) {

    public TaskState {
        attemptsUsed = requireNonNegative(attemptsUsed, "attemptsUsed");
        attempts = List.copyOf(attempts);
    }

    /**
     * The starting state of a new task at {@code stageName}: nothing burned, recorded or pinned (FR8, FR14).
     *
     * @param stageName the stage to start at; never blank
     */
    public static TaskState atStageStart(String stageName) {
        return new TaskState(
                new Position.AtStage(stageName), 0, List.of(), UsageSnapshot.EMPTY, ExecutorVersions.none());
    }

    /**
     * Appends {@code round} without burning an attempt, at the current {@code position}: a
     * {@code CannotVerify}, {@code DecisionNeeded} or {@code BudgetExhausted} round, or a pass at a
     * gate (FR13).
     *
     * @param round the executed round to record; never null
     * @param ran the describe versions of the executors that ran the round, pinned on first use; never null
     */
    public TaskState recordUnburnedRound(AttemptRecord round, ExecutorVersions ran) {
        return recorded(position, attemptsUsed, round, ran);
    }

    /**
     * Appends a quality {@code Fail} round and burns one attempt, at the current {@code position} (FR13).
     *
     * @param round the failed round to record; never null
     * @param ran the describe versions of the executors that ran the round, pinned on first use; never null
     */
    public TaskState recordQualityFailure(AttemptRecord round, ExecutorVersions ran) {
        return recorded(position, attemptsUsed + 1, round, ran);
    }

    /**
     * Returns a new state at {@code next} with the history emptied and {@code attemptsUsed} zero,
     * because the attempt history covers only the current stage (FR14); the engine picks the target,
     * so this accepts any {@link Position} (design D4). {@code totals} and the version pin are
     * carried (FR13, NFR-C1, design D5).
     *
     * @param next the position to advance to; never null
     */
    public TaskState advanceTo(Position next) {
        return rewrite(next, 0, List.of());
    }

    /**
     * Returns a new state that appends a <em>passing</em> {@code round} and, in the same value,
     * advances the position to {@code next} with {@code attemptsUsed} reset (FR4 of
     * harden-task-branch-contract). The two facts are true only together — the stage passed, so
     * the task is past it — and this is the state the round's own commit records, so a kill after
     * it can never freeze "the stage passed but the position says re-run it" and pay for the green
     * stage twice.
     *
     * <p>The round stays in {@code attempts}: that commit is the only place the passing round is
     * recorded, and the usage walk reads it there; the next stage's first round replaces the list.
     *
     * @param round the passing round to record; never null
     * @param next  the position the pass advances to; never null
     * @param ran the describe versions of the executors that ran the round, pinned on first use; never null
     */
    public TaskState recordPassAndAdvance(AttemptRecord round, Position next, ExecutorVersions ran) {
        return recorded(next, 0, round, ran);
    }

    /**
     * The state a <em>resume</em> starts the position's stage from. A recorded round that PASSED
     * belongs to the stage that passed — the same commit already advanced the position past it
     * (FR4 of harden-task-branch-contract) — so its history is not the history of the stage about
     * to run: the resumed stage starts with an empty attempt list, and its first round is round
     * zero rather than a continuation of the finished stage's numbering.
     *
     * <p>Only a resume needs this: the same normalization the live advance applies, for a state read
     * off the branch — idempotent, and a no-op unless the last recorded round passed.
     *
     * <p>A state at a gate, {@link Position.AwaitingApproval}, is returned unchanged: its passing
     * round belongs to the gate's own stage, and a pickup that pauses again carries it (FR1, FR12 of
     * make-checkpoint-gate-durable).
     *
     * <p>Implements FR4, NFR-C1 of harden-task-branch-contract; FR1 of make-checkpoint-gate-durable.
     *
     * @return this state with a finished stage's attempts dropped; never null
     */
    public TaskState startOfStage() {
        if (position instanceof Position.AwaitingApproval
                || attempts.isEmpty()
                || attempts.getLast().result() != AttemptRecord.Result.PASSED) {
            return this;
        }
        return advanceTo(position);
    }

    /**
     * The state a human decision resumes into: the same position, {@code attemptsUsed} back to
     * zero and the attempt history emptied, with the cumulative {@code totals} preserved — the
     * answered stage starts its budget over, it does not restart the task (FR4 of
     * harden-task-branch-contract).
     *
     * <p>One formula, one home: the reset rides the decision's own commit, so every caller that
     * appends a decision hands this exact state to {@code TaskRepository#appendDecision} rather
     * than spelling the reset out again. Two facts that are only true together land together.
     *
     * @return the reset state to resume from; never null
     */
    public TaskState resetAttempts() {
        return rewrite(position, 0, List.of());
    }

    /**
     * The state the approval of this gate writes: the position after the {@code manual} stage, via
     * {@link Advancement#afterGate} (the one owner of "what follows a stage"), attempt history
     * untouched — a checkpoint resets nothing (FR3 of make-checkpoint-gate-durable, D2, D7).
     *
     * @param definition the pinned pipeline the gate's stage is resolved against; never null
     * @return this state moved past the gate; never null and never itself a gate
     * @throws IllegalStateException when not at a gate, or the gate's stage is not declared
     */
    public TaskState approveGate(PipelineDefinition definition) {
        if (!(position instanceof Position.AwaitingApproval(var gate))) {
            throw new IllegalStateException("TaskState.approveGate: the position is not a gate");
        }
        var stage = definition.findStage(gate);
        if (stage == null) {
            throw new IllegalStateException("TaskState.approveGate: the gate's stage is not declared");
        }
        return rewrite(Advancement.afterGate(definition, stage), attemptsUsed, attempts);
    }

    /** A lifecycle rewrite: both carried fields, {@code totals} and the version pin, pass unchanged. */
    private TaskState rewrite(Position next, int used, List<AttemptRecord> history) {
        return new TaskState(next, used, history, totals, executorVersions);
    }

    /**
     * A round writer: appends {@code round}, folds its usage through {@link UsageFold} and pins the
     * first version per executor.
     */
    private TaskState recorded(Position next, int used, AttemptRecord round, ExecutorVersions ran) {
        var history = new ArrayList<>(attempts);
        history.add(round);
        return new TaskState(next, used, history, UsageFold.fold(totals, round), executorVersions.pinFirstUse(ran));
    }

    /**
     * Fails fast on a negative {@code attemptsUsed} (FR13); a static method, not inline, because PIT's
     * record filter would exempt a guard in the canonical constructor from the mutation gate.
     */
    private static int requireNonNegative(int value, String component) {
        if (value < 0) {
            throw new IllegalArgumentException("TaskState." + component + " must not be negative");
        }
        return value;
    }
}
