package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The one mapping from an executor result's status to attempt accounting and escalation (FR7 of
 * define-executor-contract; stage-engine "One failure-class mapping"; design D4, D10):
 *
 * <table>
 *   <caption>status → accounting</caption>
 *   <tr><th>status</th><th>accounting</th></tr>
 *   <tr><td>{@code completed}, no answer</td><td>{@link AttemptAccounting.Proceed}</td></tr>
 *   <tr><td>{@code completed} + {@code decision-request}</td><td>{@link AttemptAccounting.EndStage}
 *       {@code DECISION_NEEDED}</td></tr>
 *   <tr><td>{@code failed/quality}</td><td>{@link AttemptAccounting.BurnAttempt}</td></tr>
 *   <tr><td>{@code failed/infrastructure}</td><td>{@link AttemptAccounting.Escalate}
 *       {@code CannotExecute}</td></tr>
 *   <tr><td>{@code failed/limit}</td><td>{@link AttemptAccounting.EndStage}
 *       {@code BUDGET_EXHAUSTED}</td></tr>
 *   <tr><td>{@code pending}</td><td>{@link AttemptAccounting.Escalate} {@code CannotExecute}</td></tr>
 * </table>
 *
 * <p>{@code pending} is {@code infrastructure}: no stage round can be polled in this version, so
 * every role the engine runs is a role it cannot poll. A thrown executor failure reaches this class
 * as the {@code failed/infrastructure} result {@link RoundExecution} builds from it, so it takes
 * the same row. The executor's {@code infrastructure} retries are the adapter's own and are
 * already spent when its result arrives. No adapter decides a class: this is the only switch over
 * an {@link ExecutionResult}'s status, and the only constructor of {@link
 * EscalationReport.CannotExecute} outside the state mappers that rebuild one from the wire.
 *
 * <p>Pure and stateless (NFR-R1).
 *
 * <p>Implements FR7, FR2 of define-executor-contract.
 */
final class ExecutorOutcomeClassifier {

    /**
     * The reason a {@code pending} result escalates with: no caller of a stage round can poll in
     * this version (wire-contract "Pending from a role whose caller cannot poll"; the pending key
     * is the executor's opaque text and is not quoted into the factory's own prose).
     */
    static final UntrustedText UNPOLLABLE =
            UntrustedText.factory("the executor returned pending, which this stage cannot poll (unsupported pending)");

    private ExecutorOutcomeClassifier() {}

    /**
     * Classifies one executor result. Exhaustive switches, no {@code default}, so a new status or
     * failure class is a compile error here and nowhere else.
     *
     * @param result the round's result, reported or built from a thrown failure; never null
     * @return the attempt accounting the result implies; never null
     */
    static AttemptAccounting classify(ExecutionResult result) {
        return switch (result.status()) {
            case ExecutionResult.Completed ignored ->
                result.answer() instanceof ExecutionResult.DecisionRequest(var question, var options)
                        ? new AttemptAccounting.EndStage(
                                AttemptRecord.Result.DECISION_NEEDED, new Stop.DecisionNeeded(question, options))
                        : new AttemptAccounting.Proceed();
            case ExecutionResult.Failed(var failureClass, var reason, var findings) ->
                switch (failureClass) {
                    case QUALITY -> new AttemptAccounting.BurnAttempt(findings);
                    case INFRASTRUCTURE ->
                        new AttemptAccounting.Escalate(new EscalationReport.CannotExecute(reason, result.denials()));
                    case LIMIT ->
                        new AttemptAccounting.EndStage(
                                AttemptRecord.Result.BUDGET_EXHAUSTED, new Stop.BudgetExhausted(reason));
                };
            case ExecutionResult.Pending ignored ->
                new AttemptAccounting.Escalate(new EscalationReport.CannotExecute(UNPOLLABLE, result.denials()));
        };
    }
}
