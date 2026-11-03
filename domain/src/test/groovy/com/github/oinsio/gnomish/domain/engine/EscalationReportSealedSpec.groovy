package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * EscalationReport as a sealed value hierarchy: an exhaustive switch handles all
 * six variants and equal components make equal reports (UX1, design D1).
 * Implements FR10 of add-stage-engine.
 *
 * <p>Per-variant construction and validation rules live in {@code EscalationReportSpec}.
 */
class EscalationReportSealedSpec extends Specification {

    private static CheckRef sampleCheck() {
        new CheckRef(0, UntrustedText.manifest('command:./gradlew build'))
    }

    // FR10: EscalationReport is sealed — an exhaustive switch handles all six variants (FR7 of
    //     define-executor-contract added BudgetExhausted)
    def "an exhaustive switch over EscalationReport handles all six variants"() {
        expect: 'each variant is matched to its own arm'
        describe(report) == expected

        where:
        report | expected
        new EscalationReport.AttemptsExhausted(3) | 'exhausted: 3'
        new EscalationReport.DecisionNeeded(UntrustedText.agent('Q?'), []) | 'decision: Q?'
        new EscalationReport.CannotVerify(sampleCheck(), UntrustedText.subprocess('down'), UntrustedText.subprocess('')) | 'cannot-verify: down'
        new EscalationReport.PipelineMismatch(new MismatchCause.StaleStage(UntrustedText.branchDocument('legacy'))) | 'mismatch: legacy'
        new EscalationReport.CannotExecute(UntrustedText.subprocess('boom'), []) | 'cannot-execute: boom'
        new EscalationReport.BudgetExhausted(UntrustedText.agent('tokens 1000')) | 'budget: tokens 1000'
    }

    // FR7 of define-executor-contract: the report vocabulary is closed at six kinds
    def "permits exactly the six report kinds"() {
        expect:
        EscalationReport.permittedSubclasses*.simpleName as Set == [
            'AttemptsExhausted',
            'DecisionNeeded',
            'CannotVerify',
            'PipelineMismatch',
            'CannotExecute',
            'BudgetExhausted'
        ] as Set
    }

    // FR10: reports are values — equal content means equal reports
    def "reports with the same components are equal values"() {
        expect: 'equal content is equal for each kind'
        new EscalationReport.AttemptsExhausted(2) == new EscalationReport.AttemptsExhausted(2)
        new EscalationReport.DecisionNeeded(UntrustedText.agent('Q?'), [UntrustedText.agent('a')]) == new EscalationReport.DecisionNeeded(UntrustedText.agent('Q?'), [UntrustedText.agent('a')])
        new EscalationReport.CannotVerify(sampleCheck(), UntrustedText.subprocess('r'), UntrustedText.subprocess('d')) ==
                new EscalationReport.CannotVerify(sampleCheck(), UntrustedText.subprocess('r'), UntrustedText.subprocess('d'))
        new EscalationReport.PipelineMismatch(new MismatchCause.StaleStage(UntrustedText.branchDocument('s'))) == new EscalationReport.PipelineMismatch(new MismatchCause.StaleStage(UntrustedText.branchDocument('s')))
        new EscalationReport.CannotExecute(UntrustedText.subprocess('c'), []) == new EscalationReport.CannotExecute(UntrustedText.subprocess('c'), [])
        new EscalationReport.BudgetExhausted(UntrustedText.agent('b')) == new EscalationReport.BudgetExhausted(UntrustedText.agent('b'))

        and: 'differing content makes them unequal'
        new EscalationReport.AttemptsExhausted(1) != new EscalationReport.AttemptsExhausted(2)
        new EscalationReport.PipelineMismatch(new MismatchCause.StaleStage(UntrustedText.branchDocument('a'))) != new EscalationReport.PipelineMismatch(new MismatchCause.StaleStage(UntrustedText.branchDocument('b')))
        new EscalationReport.BudgetExhausted(UntrustedText.agent('a')) != new EscalationReport.BudgetExhausted(UntrustedText.agent('b'))
    }

    private static String describe(EscalationReport report) {
        switch (report) {
            case EscalationReport.AttemptsExhausted:
                return 'exhausted: ' + ((EscalationReport.AttemptsExhausted) report).limit()
            case EscalationReport.DecisionNeeded:
                return 'decision: ' + ((EscalationReport.DecisionNeeded) report).question()
            case EscalationReport.CannotVerify:
                return 'cannot-verify: ' + ((EscalationReport.CannotVerify) report).reason()
            case EscalationReport.PipelineMismatch:
                return 'mismatch: ' + ((EscalationReport.PipelineMismatch) report).cause().stage()
            case EscalationReport.CannotExecute:
                return 'cannot-execute: ' + ((EscalationReport.CannotExecute) report).cause()
            case EscalationReport.BudgetExhausted:
                return 'budget: ' + ((EscalationReport.BudgetExhausted) report).bound()
            default: throw new IllegalStateException('unreachable')
        }
    }
}
