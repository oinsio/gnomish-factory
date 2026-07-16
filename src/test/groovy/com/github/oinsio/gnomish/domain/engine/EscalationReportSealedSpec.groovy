package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * EscalationReport as a sealed value hierarchy: an exhaustive switch handles all
 * five variants and equal components make equal reports (UX1, design D1).
 * Implements FR10 of add-stage-engine.
 *
 * <p>Per-variant construction and validation rules live in {@code EscalationReportSpec}.
 */
class EscalationReportSealedSpec extends Specification {

    private static CheckRef sampleCheck() {
        new CheckRef(0, 'command:./gradlew build')
    }

    // FR10: EscalationReport is sealed — an exhaustive switch handles all five variants
    def "an exhaustive switch over EscalationReport handles all five variants"() {
        expect: 'each variant is matched to its own arm'
        describe(report) == expected

        where:
        report                                                                       | expected
        new EscalationReport.AttemptsExhausted(3)                                    | 'exhausted: 3'
        new EscalationReport.DecisionNeeded('Q?', [])                                | 'decision: Q?'
        new EscalationReport.CannotVerify(sampleCheck(), 'down', '')                 | 'cannot-verify: down'
        new EscalationReport.PipelineMismatch('legacy')                              | 'mismatch: legacy'
        new EscalationReport.CannotExecute('boom')                                   | 'cannot-execute: boom'
    }

    // FR10: reports are values — equal content means equal reports
    def "reports with the same components are equal values"() {
        expect: 'equal content is equal for each kind'
        new EscalationReport.AttemptsExhausted(2) == new EscalationReport.AttemptsExhausted(2)
        new EscalationReport.DecisionNeeded('Q?', ['a']) == new EscalationReport.DecisionNeeded('Q?', ['a'])
        new EscalationReport.CannotVerify(sampleCheck(), 'r', 'd') ==
                new EscalationReport.CannotVerify(sampleCheck(), 'r', 'd')
        new EscalationReport.PipelineMismatch('s') == new EscalationReport.PipelineMismatch('s')
        new EscalationReport.CannotExecute('c') == new EscalationReport.CannotExecute('c')

        and: 'differing content makes them unequal'
        new EscalationReport.AttemptsExhausted(1) != new EscalationReport.AttemptsExhausted(2)
        new EscalationReport.PipelineMismatch('a') != new EscalationReport.PipelineMismatch('b')
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
                return 'mismatch: ' + ((EscalationReport.PipelineMismatch) report).staleStage()
            case EscalationReport.CannotExecute:
                return 'cannot-execute: ' + ((EscalationReport.CannotExecute) report).cause()
            default: throw new IllegalStateException('unreachable')
        }
    }
}
