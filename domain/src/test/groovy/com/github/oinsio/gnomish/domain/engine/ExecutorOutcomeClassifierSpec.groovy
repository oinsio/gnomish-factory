package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * The one failure-class mapping (FR7, FR2 of define-executor-contract; design D4, D10): every
 * status and failure class of an {@link ExecutionResult} maps to one {@link AttemptAccounting},
 * one row per case. The engine-level effect of each row is pinned by {@link
 * ExecutorOutcomeTableSpec}.
 *
 * <p>Implements FR7, FR2 of define-executor-contract.
 */
class ExecutorOutcomeClassifierSpec extends Specification {

    static final def KEY = new AttemptKey('TASK-1', 'build', 0)
    static final def FINDING = new Finding('the migration drops a column', 'V3.sql:12', 'irreversible')
    static final def DENIAL = new Denial(new Finding('egress denied: evil.example.com:443', null, null), null)
    static final def REASON = UntrustedText.agent('reported reason')

    static ExecutionResult result(ExecutionResult.Status status, ExecutionResult.Answer answer = null) {
        new ExecutionResult(status, answer, ExecutorUsage.none(), new ToolTrace(KEY, []), [DENIAL])
    }

    static ExecutionResult.Failed failed(ExecutionResult.FailureClass failureClass) {
        new ExecutionResult.Failed(failureClass, REASON, [FINDING], null)
    }

    // FR7, FR2: each status and class maps to exactly the accounting the table prescribes
    def "#row classifies as its table accounting"() {
        expect:
        ExecutorOutcomeClassifier.classify(input) == accounting

        where:
        row | input | accounting
        'completed' | result(new ExecutionResult.Completed()) | new AttemptAccounting.Proceed()
        'completed + decision-request' | result(new ExecutionResult.Completed(),
                new ExecutionResult.DecisionRequest(UntrustedText.agent('which db?'), [UntrustedText.agent('pg')])) |
                new AttemptAccounting.EndStage(AttemptRecord.Result.DECISION_NEEDED,
                new Stop.DecisionNeeded(UntrustedText.agent('which db?'), [UntrustedText.agent('pg')]))
        'failed/quality' | result(failed(ExecutionResult.FailureClass.QUALITY)) | new AttemptAccounting.BurnAttempt([FINDING])
        'failed/infrastructure' | result(failed(ExecutionResult.FailureClass.INFRASTRUCTURE)) |
                new AttemptAccounting.Escalate(new EscalationReport.CannotExecute(REASON, [DENIAL]))
        'failed/limit' | result(failed(ExecutionResult.FailureClass.LIMIT)) |
                new AttemptAccounting.EndStage(AttemptRecord.Result.BUDGET_EXHAUSTED, new Stop.BudgetExhausted(REASON))
        'pending (transform)' | result(new ExecutionResult.Pending(new PendingKey('k-1'), null, PendingState.RUNNING, null, null)) |
                new AttemptAccounting.Escalate(new EscalationReport.CannotExecute(ExecutorOutcomeClassifier.UNPOLLABLE, [DENIAL]))
    }

    // FR7: the table covers every failure class — a new class without a row fails here
    def "the table names every failure class"() {
        expect:
        ExecutionResult.FailureClass.values().toList() == [
            ExecutionResult.FailureClass.QUALITY,
            ExecutionResult.FailureClass.INFRASTRUCTURE,
            ExecutionResult.FailureClass.LIMIT
        ]
        ExecutionResult.Status.permittedSubclasses*.simpleName as Set == [
            'Completed',
            'Failed',
            'Pending'
        ] as Set
    }

    // FR7: a burn copies the findings it carries
    def "BurnAttempt copies its findings"() {
        given:
        def source = [FINDING]

        when:
        def burn = new AttemptAccounting.BurnAttempt(source)
        source.clear()

        then:
        burn.findings() == [FINDING]
    }

    static final def PENDING = new ExecutionResult.Pending(new PendingKey('k-1'), null, PendingState.RUNNING, null, null)
    static final def QUESTION = new ExecutionResult.DecisionRequest(UntrustedText.agent('which db?'), [UntrustedText.agent('pg')])
    static final def REPLY = new ExecutionResult.VerdictReply(new VerdictAnswer.Sarif([]))

    // FR19, FR2 of migrate-verify-to-executor-contract (D21, D22; human decision 2026-11-12): the
    // readers ask the owner instead of reading the status, and each answer is the table read once
    def "the owner answers its readers for #row: verifiable #verifiable, pending #pending, failure #failure"() {
        expect:
        ExecutorOutcomeClassifier.verifiable(input) == verifiable
        ExecutorOutcomeClassifier.pending(input) == Optional.ofNullable(pending ? input.status() : null)
        ExecutorOutcomeClassifier.failure(input) == Optional.ofNullable(failure ? input.status() : null)

        where:
        row | input || verifiable | pending | failure
        'completed' | result(new ExecutionResult.Completed()) || true | false | false
        'completed + verdict reply' | result(new ExecutionResult.Completed(), REPLY) || true | false | false
        'completed + decision-request' | result(new ExecutionResult.Completed(), QUESTION) || false | false | false
        'failed/quality' | result(failed(ExecutionResult.FailureClass.QUALITY)) || false | false | true
        'failed/infrastructure' | result(failed(ExecutionResult.FailureClass.INFRASTRUCTURE)) || false | false | true
        'failed/limit' | result(failed(ExecutionResult.FailureClass.LIMIT)) || false | false | true
        'pending' | result(PENDING) || false | true | false
    }
}
