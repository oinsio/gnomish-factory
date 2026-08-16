package com.github.oinsio.gnomish.domain.engine;

import java.util.List;

/**
 * One round shaped for the {@link StageAttemptLoop} to route: {@link Verified} — the
 * executor completed and the verify chain ran — {@link NeedsDecision} — the executor asked
 * a human and no chain ran (FR6) — or {@link CannotExecute} — the executor port threw and
 * no round ran at all (FR10). A sealed carrier so the loop records, persists, events and
 * routes each round without re-deriving any of it.
 *
 * <p>Implements FR4, FR6, FR10, FR13 of add-stage-engine.
 */
sealed interface RoundOutcome permits RoundOutcome.Verified, RoundOutcome.NeedsDecision, RoundOutcome.CannotExecute {

    /**
     * A round the executor completed and the verify chain ran on: the {@link AttemptKey}
     * it ran under, its recorded {@link AttemptRecord}, the overall {@link Verdict} derived
     * from the chain, and the executor's raw {@link ToolTrace}.
     *
     * @param key the round's correlation key; never null
     * @param record the round's recorded metrics and check results; never null
     * @param verdict the round's overall verdict; never null
     * @param trace the executor's raw tool trace for the round; never null
     */
    record Verified(AttemptKey key, AttemptRecord record, Verdict verdict, ToolTrace trace) implements RoundOutcome {}

    /**
     * A round whose executor asked a human instead of completing (FR6, design D6): no
     * verify chain ran. Carries the {@link AttemptKey}, the {@link AttemptRecord} (executor
     * metrics, no check results — FR13), the executor's raw {@link ToolTrace}, and the
     * {@code question}/{@code options} the engine escalates verbatim without interpreting.
     *
     * @param key the round's correlation key; never null
     * @param record the round's recorded metrics, no check results; never null
     * @param trace the executor's raw tool trace for the round; never null
     * @param question what the human must decide, carried verbatim; never null
     * @param options the candidate answers, carried verbatim; never null, possibly empty
     */
    record NeedsDecision(AttemptKey key, AttemptRecord record, ToolTrace trace, String question, List<String> options)
            implements RoundOutcome {}

    /**
     * A round whose executor port threw — its own retries exhausted — so no round ran at
     * all (FR10): no {@link AttemptRecord}, no verify chain, no persisted state. The loop
     * escalates it as {@link EscalationReport.CannotExecute} without burning an attempt.
     *
     * @param key the correlation key of the round that could not run; never null
     * @param cause the executor failure's preserved stack trace (NFR-O1); never null
     */
    record CannotExecute(AttemptKey key, String cause) implements RoundOutcome {}
}
