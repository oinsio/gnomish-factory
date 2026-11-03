package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The executor-failure side of one round, for {@link RoundExecution}: the one operator line of
 * every executor failure (logging.md, "One failure, one log"), and the turn of a THROWN failure
 * into the {@code failed/infrastructure} {@link ExecutionResult} the {@link
 * ExecutorOutcomeClassifier} takes, so a throw and a reported infrastructure failure are classified
 * by one row (FR7 of define-executor-contract, design D10; FR10, NFR-O1 of add-stage-engine).
 *
 * <ul>
 *   <li>A throw — the port's own retries exhausted — is an ERROR under {@link
 *       OperatorEvent#EXECUTOR_THREW} with the cause as the throwable, since only here is the
 *       throwable in hand.
 *   <li>A reported {@code failed/infrastructure}, or a {@code pending} the stage cannot poll
 *       ({@link AttemptAccounting.Escalate}), is a WARN under {@link
 *       OperatorEvent#EXECUTOR_REPORTED_FAILURE} naming the round key and the reason.
 *   <li>A reported {@code failed/limit} ({@link Stop.BudgetExhausted}) is a WARN under {@link
 *       OperatorEvent#EXECUTOR_BUDGET_EXHAUSTED} naming the bound.
 * </ul>
 *
 * <p>Every other accounting — a completed round, a decision request, a quality failure the next
 * attempt answers — writes nothing here. Stateless (NFR-R1).
 *
 * <p>Implements FR10, NFR-O1 of add-stage-engine; FR7, NFR-O2 of define-executor-contract.
 */
final class RoundFailures {

    private static final Logger log = LoggerFactory.getLogger(RoundFailures.class);

    private RoundFailures() {}

    /**
     * Logs a thrown executor failure at ERROR — its one operator line — and turns it into the
     * {@code failed/infrastructure} result the classifier takes (design D10): the rendered stack
     * trace as its reason, no usage, an empty trace, no denials. An adapter that ran a round of
     * its own reports its failure as a result, with the round's denials, instead of throwing
     * (FR7 of define-executor-contract); a throw is what fails before any round exists.
     */
    static ExecutionResult thrown(AttemptKey key, RuntimeException ex) {
        log.error(OperatorEvent.EXECUTOR_THREW.head() + "executor threw for {}", key, ex);
        var status = new ExecutionResult.Failed(
                ExecutionResult.FailureClass.INFRASTRUCTURE, StackTraces.render(ex), List.of());
        return new ExecutionResult(status, null, ExecutorUsage.none(), new ToolTrace(key, List.of()), List.of());
    }

    /**
     * Writes the round's operator line when its accounting is a reported failure.
     *
     * @param key the round's correlation key; never null
     * @param accounting the classifier's accounting of the reported result; never null
     */
    static void reported(AttemptKey key, AttemptAccounting accounting) {
        if (accounting instanceof AttemptAccounting.Escalate(var report)) {
            log.warn(
                    OperatorEvent.EXECUTOR_REPORTED_FAILURE.head()
                            + "executor reported a failure the stage cannot run past for {}: {}",
                    key,
                    report.cause().forLog());
        } else if (accounting instanceof AttemptAccounting.EndStage end
                && end.stop() instanceof Stop.BudgetExhausted(var bound)) {
            log.warn(
                    OperatorEvent.EXECUTOR_BUDGET_EXHAUSTED.head() + "executor reached a bound for {}: {}",
                    key,
                    bound.forLog());
        }
    }
}
