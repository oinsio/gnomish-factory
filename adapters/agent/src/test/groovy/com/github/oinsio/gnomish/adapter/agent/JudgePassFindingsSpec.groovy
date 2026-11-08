package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR7, D5 as amended of migrate-verify-to-executor-contract: a judge's {@code "passed": true}
 * verdict keeps its findings, read under the same strict schema as a failed one — a pass is
 * no longer findings-less, and a malformed {@code findings} array is never a pass.
 */
class JudgePassFindingsSpec extends Specification {

    def extractor = new JudgeVerdictExtractor()

    // Scenario: verification/verdict-executors / A judge pass keeps its findings
    def "FR7: a passing judge vote carries its findings in the order written"() {
        when:
        def verdict = extractor.extract(UntrustedText.agent('{"passed": true, "findings": ["x", "y"]}'))

        then: 'the vote is a pass carrying both findings, in order'
        verdict == new Verdict.Pass(null, [
            new Finding('x', null, null),
            new Finding('y', null, null)
        ])
    }

    def "FR7: a malformed findings array makes a passing judge vote CannotVerify (#label)"() {
        when:
        def verdict = extractor.extract(UntrustedText.agent(message))

        then:
        verdict instanceof Verdict.CannotVerify

        where:
        label | message
        'structured entry' | '{"passed": true, "findings": [{"message": "smuggled"}]}'
        'blank entry' | '{"passed": true, "findings": ["real", "   "]}'
        'non-string entry' | '{"passed": true, "findings": [42]}'
    }

    def "FR7: a passing judge vote with no findings array carries no findings"() {
        expect:
        extractor.extract(UntrustedText.agent(message)) == new Verdict.Pass(null, [])

        where:
        message << [
            '{"passed": true}',
            '{"passed": true, "findings": "not an array"}'
        ]
    }
}
