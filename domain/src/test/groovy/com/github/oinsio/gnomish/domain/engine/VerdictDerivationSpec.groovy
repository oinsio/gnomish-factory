package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * VerdictDerivation: the one engine component that derives a verify check's verdict from its
 * executor's answer against the check's threshold (FR7, design D5 of
 * migrate-verify-to-executor-contract).
 */
class VerdictDerivationSpec extends Specification {

    // FR7: every result level against every threshold — a result at or above the threshold fails
    def "FR7: a #level result under threshold #threshold #outcome"() {
        given:
        def finding = new Finding("result at ${level}", 'src/A.java:3', null)
        def answer = new VerdictAnswer.Sarif([
            new VerdictAnswer.Result(level, finding)
        ])

        when:
        def verdict = VerdictDerivation.derive(answer, threshold)

        then:
        verdict == (fails ? new Verdict.Fail([finding]) : new Verdict.Pass())

        where:
        level | threshold || fails
        ResultLevel.NONE | Threshold.ERROR || false
        ResultLevel.NOTE | Threshold.ERROR || false
        ResultLevel.WARNING | Threshold.ERROR || false
        ResultLevel.ERROR | Threshold.ERROR || true
        ResultLevel.NONE | Threshold.WARNING || false
        ResultLevel.NOTE | Threshold.WARNING || false
        ResultLevel.WARNING | Threshold.WARNING || true
        ResultLevel.ERROR | Threshold.WARNING || true
        ResultLevel.NONE | Threshold.NOTE || false
        ResultLevel.NOTE | Threshold.NOTE || true
        ResultLevel.WARNING | Threshold.NOTE || true
        ResultLevel.ERROR | Threshold.NOTE || true

        outcome = fails ? 'fails' : 'passes'
    }

    // FR7: the table above covers every level and every threshold, so a constant added on one side fails here
    def "FR7: the level x threshold table is complete"() {
        expect:
        ResultLevel.values().toList() == [
            ResultLevel.NONE,
            ResultLevel.NOTE,
            ResultLevel.WARNING,
            ResultLevel.ERROR
        ]
        Threshold.values().toList() == [
            Threshold.ERROR,
            Threshold.WARNING,
            Threshold.NOTE
        ]
    }

    // FR7: an answer with no results passes under every threshold
    def "FR7: an empty SARIF answer passes under threshold #threshold"() {
        expect:
        VerdictDerivation.derive(new VerdictAnswer.Sarif([]), threshold) == new Verdict.Pass()

        where:
        threshold << Threshold.values()
    }

    // FR7: warnings alone do not fail the default threshold
    def "FR7: an answer carrying only warnings passes under the default threshold"() {
        given:
        def answer = new VerdictAnswer.Sarif([
            result(ResultLevel.WARNING, 'w1'),
            result(ResultLevel.WARNING, 'w2'),
        ])

        expect:
        VerdictDerivation.derive(answer, Threshold.DEFAULT) == new Verdict.Pass()
    }

    // Scenario: verification/verdict-executors / Legacy findings file is normalized
    def "FR7: one error result, as the legacy findings file normalizes to, fails the default threshold"() {
        given:
        def finding = new Finding('x', null, null)

        expect:
        VerdictDerivation.derive(new VerdictAnswer.Sarif([
            new VerdictAnswer.Result(ResultLevel.ERROR, finding)
        ]),
        Threshold.DEFAULT) == new Verdict.Fail([finding])
    }

    // FR7: a failure carries every result as a finding, in the order written, advisory ones included
    def "FR7: a failing answer carries every result as a finding in the order written"() {
        given:
        def answer = new VerdictAnswer.Sarif([
            result(ResultLevel.NOTE, 'n'),
            result(ResultLevel.ERROR, 'e'),
            result(ResultLevel.WARNING, 'w'),
        ])

        when:
        def verdict = VerdictDerivation.derive(answer, Threshold.ERROR)

        then:
        verdict instanceof Verdict.Fail
        (verdict as Verdict.Fail).findings()*.message() == ['n', 'e', 'w']
    }

    // FR7: a failing result anywhere fails the check, not only the first or the last
    def "FR7: an error between passing results still fails"() {
        given:
        def answer = new VerdictAnswer.Sarif([
            result(ResultLevel.ERROR, 'e'),
            result(ResultLevel.NOTE, 'n'),
        ])

        expect:
        VerdictDerivation.derive(answer, Threshold.ERROR) instanceof Verdict.Fail
    }

    // FR7 (FR6 of define-executor-contract): a malformed answer is cannot-verify naming the parse error, never a pass
    def "FR7: a malformed answer is cannot-verify quoting the reading's reason, under threshold #threshold"() {
        given:
        def reason = UntrustedText.subprocess('runs[0].results[0].level: unknown level "fatal"')

        when:
        def verdict = VerdictDerivation.derive(new VerdictAnswer.Malformed(reason), threshold)

        then:
        verdict instanceof Verdict.CannotVerify
        with(verdict as Verdict.CannotVerify) {
            it.reason() == UntrustedText.factory('the verdict answer is malformed')
            it.details() == reason
        }

        where:
        threshold << Threshold.values()
    }

    // FR7: the answer copies its results, so a later edit of the caller's list changes nothing
    def "FR7: a SARIF answer is inert once constructed"() {
        given:
        def results = [result(ResultLevel.NOTE, 'n')]
        def answer = new VerdictAnswer.Sarif(results)

        when:
        results << result(ResultLevel.ERROR, 'e')

        then:
        answer.results().size() == 1
        VerdictDerivation.derive(answer, Threshold.ERROR) == new Verdict.Pass()
    }

    private static VerdictAnswer.Result result(ResultLevel level, String message) {
        new VerdictAnswer.Result(level, new Finding(message, null, null))
    }
}
