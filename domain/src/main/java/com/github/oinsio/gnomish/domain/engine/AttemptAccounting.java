package com.github.oinsio.gnomish.domain.engine;

import java.util.List;

/**
 * What one executor result means for the stage's attempt accounting — the one answer {@link
 * ExecutorOutcomeClassifier} gives (FR7 of define-executor-contract, design D4, D10). The round
 * mechanics shape a round from it and the attempt loop routes on it; neither reads the result's
 * status again.
 *
 * <ul>
 *   <li>{@link Proceed} — {@code completed} with no answer: the verify chain judges the round, and
 *       its verdict decides whether an attempt is burned.
 *   <li>{@link BurnAttempt} — {@code failed/quality}: the round is recorded with the executor's
 *       findings and burns one attempt; no check runs.
 *   <li>{@link EndStage} — the round is recorded WITHOUT burning an attempt and its {@link Stop}
 *       ends the stage: a {@code decision-request} answer ({@link Stop.DecisionNeeded}) or {@code
 *       failed/limit} ({@link Stop.BudgetExhausted}).
 *   <li>{@link Escalate} — {@code failed/infrastructure}, a thrown executor failure, or {@code
 *       pending} the stage cannot poll: nothing is recorded, no attempt is burned, and the stage
 *       escalates {@link EscalationReport.CannotExecute} from the state the round started from.
 * </ul>
 *
 * <p>Implements FR7 of define-executor-contract.
 */
sealed interface AttemptAccounting
        permits AttemptAccounting.Proceed,
                AttemptAccounting.BurnAttempt,
                AttemptAccounting.EndStage,
                AttemptAccounting.Escalate {

    /** A {@code completed} round with no answer: the verify chain's verdict decides. */
    record Proceed() implements AttemptAccounting {}

    /**
     * A {@code failed/quality} round: recorded with the executor's findings, one attempt burned.
     *
     * @param findings the executor's findings, fed to the next attempt; defensively copied
     */
    record BurnAttempt(List<Finding> findings) implements AttemptAccounting {

        public BurnAttempt {
            findings = List.copyOf(findings);
        }
    }

    /**
     * A round recorded unburned whose stop ends the stage; {@code result} is the record's
     * classification and {@code stop} the escalation content it carries, so the escalation and a
     * later pickup's re-escalation both read it from the record (FR5 of
     * make-checkpoint-gate-durable).
     *
     * @param result the recorded round's classification, of the kind {@code stop} names; never null
     * @param stop the stop the record carries; never null and never {@link Stop.None}
     */
    record EndStage(AttemptRecord.Result result, Stop stop) implements AttemptAccounting {}

    /**
     * A round that records nothing and escalates its report from the state it started from.
     *
     * @param report the escalation the stage ends with; never null
     */
    record Escalate(EscalationReport.CannotExecute report) implements AttemptAccounting {}
}
