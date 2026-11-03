package com.github.oinsio.gnomish.domain.engine;

/**
 * One round shaped for the {@link StageAttemptLoop} to route, from the {@link AttemptAccounting}
 * the {@link ExecutorOutcomeClassifier} gave its result (FR7 of define-executor-contract): {@link
 * Judged} — the round reached a verdict, from the verify chain after a {@code completed} result or
 * from the executor's own {@code failed/quality} report with no chain run; {@link Stopped} — the
 * round was recorded unburned with a stop that ends the stage (a decision request, FR6; a reached
 * bound); or {@link Unrecorded} — the executor threw, reported {@code failed/infrastructure} or a
 * {@code pending} the stage cannot poll, and no round was recorded (FR10). A sealed carrier so the
 * loop records, persists, events and routes each round without re-deriving any of it.
 *
 * <p>Implements FR4, FR6, FR10, FR13 of add-stage-engine; FR7 of define-executor-contract.
 */
sealed interface RoundOutcome permits RoundOutcome.Judged, RoundOutcome.Stopped, RoundOutcome.Unrecorded {

    /**
     * A round that reached a verdict: the {@link AttemptKey} it ran under, its recorded {@link
     * AttemptRecord}, the overall {@link Verdict} — derived from the verify chain, or the {@link
     * Verdict.Fail} of an executor-reported {@code failed/quality} round whose findings the record
     * carries as its one check result (FR7 of define-executor-contract) — and the executor's raw
     * {@link ToolTrace}.
     *
     * @param key the round's correlation key; never null
     * @param record the round's recorded metrics and check results; never null
     * @param verdict the round's overall verdict; never null
     * @param trace the executor's raw tool trace for the round; never null
     */
    record Judged(AttemptKey key, AttemptRecord record, Verdict verdict, ToolTrace trace) implements RoundOutcome {}

    /**
     * A round recorded without burning an attempt whose stop ends the stage ({@link
     * AttemptAccounting.EndStage}): no verify chain ran. The record carries the stop — the
     * executor's question and options ({@link Stop.DecisionNeeded}, FR6) or the bound it reached
     * ({@link Stop.BudgetExhausted}, FR7 of define-executor-contract) — so the escalation the loop
     * raises and a later pickup's re-escalation both read the same record (FR5 of
     * make-checkpoint-gate-durable).
     *
     * @param key the round's correlation key; never null
     * @param record the round's recorded metrics and its stop, no check results; never null
     * @param trace the executor's raw tool trace for the round; never null
     */
    record Stopped(AttemptKey key, AttemptRecord record, ToolTrace trace) implements RoundOutcome {}

    /**
     * A round that records nothing ({@link AttemptAccounting.Escalate}, FR10): no {@link
     * AttemptRecord}, no verify chain, no persisted state. The loop escalates the accounting's
     * {@link EscalationReport.CannotExecute} — cause and the failed round's denials (FR1 of
     * fix-denial-attribution-durability) — from the state the round started from, without
     * burning an attempt.
     *
     * @param key the correlation key of the round that could not run; never null
     * @param escalation the classifier's accounting carrying the report; never null
     */
    record Unrecorded(AttemptKey key, AttemptAccounting.Escalate escalation) implements RoundOutcome {}
}
