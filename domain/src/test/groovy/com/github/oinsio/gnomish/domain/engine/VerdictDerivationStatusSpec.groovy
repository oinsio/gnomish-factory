package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * VerdictDerivation over the answers that carry no levels to judge — a failed status — and over the
 * platform run an answer came from (FR7, design D5, D9 of migrate-verify-to-executor-contract): every
 * check path hands the engine an answer, and the derivation alone turns it into a verdict.
 */
class VerdictDerivationStatusSpec extends Specification {

    // FR7: a quality failure status has no levels to judge, so it fails with its findings, none included
    def "FR7: a quality failure is a fail carrying its findings under threshold #threshold"() {
        expect:
        VerdictDerivation.derive(new VerdictAnswer.QualityFailure(findings), threshold) == new Verdict.Fail(findings)

        where:
        [threshold, findings] << [
            Threshold.values().toList(),
            [
                [],
                [
                    new Finding('red', 'src/A.java', 'exit 1')
                ]
            ]
        ].combinations()
    }

    // FR7 (FR4 of add-stage-engine): an infrastructure failure is cannot-verify quoting its reason and details
    def "FR7: an infrastructure failure is cannot-verify with its reason and details under threshold #threshold"() {
        given:
        def reason = UntrustedText.subprocess('binary not found')
        def details = UntrustedText.subprocess('sh: 1: lint: not found')

        expect:
        VerdictDerivation.derive(new VerdictAnswer.InfrastructureFailure(reason, details), threshold) ==
                new Verdict.CannotVerify(reason, details)

        where:
        threshold << Threshold.values()
    }

    // FR7 (NFR-O2 of add-sandbox-core): a pass keeps the platform run URL the answer came from
    def "FR7: a passing answer carries its platform run URL"() {
        given:
        def answer = new VerdictAnswer.Sarif([
            result(ResultLevel.NOTE, 'n')
        ], 'https://ci.example/runs/7')

        expect:
        VerdictDerivation.derive(answer, Threshold.ERROR) == new Verdict.Pass('https://ci.example/runs/7', [new Finding('n', null, null)])
    }

    // FR7: a failing answer fails whatever run it came from
    def "FR7: a failing answer with a run URL is a fail"() {
        given:
        def finding = new Finding('e', null, null)

        expect:
        VerdictDerivation.derive(new VerdictAnswer.Sarif([
            new VerdictAnswer.Result(ResultLevel.ERROR, finding)
        ], 'https://ci.example/runs/7'), Threshold.ERROR) == new Verdict.Fail([finding])
    }

    // FR7: a quality failure copies its findings, so a later edit of the caller's list changes nothing
    def "FR7: a quality failure is inert once constructed"() {
        given:
        def findings = [new Finding('a', null, null)]
        def answer = new VerdictAnswer.QualityFailure(findings)

        when:
        findings << new Finding('b', null, null)

        then:
        answer.findings()*.message() == ['a']
    }

    private static VerdictAnswer.Result result(ResultLevel level, String message) {
        new VerdictAnswer.Result(level, new Finding(message, null, null))
    }
}
