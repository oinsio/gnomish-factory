package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * D5 of add-agent-executor: the judge's final message is free text, so the verdict is the first JSON
 * object in it — prose around it is ignored, and a later object never overrides it. Companion to
 * {@code JudgeVerdictExtractorSpec}, which covers the fences and every no-verdict path.
 */
class JudgeVerdictFirstObjectSpec extends Specification {

    // D5: the first object decides, whatever follows it
    // Scenario: executor/agent-executor / The first JSON object is the verdict
    def "D5: the vote is read from the first JSON object of a message with #shape"() {
        expect:
        new JudgeVerdictExtractor().extract(UntrustedText.agent(message)).class == expected

        where:
        shape | message || expected
        'prose around one object' | 'I checked it. {"passed": false, "findings": ["no tests"]} Done.' || Verdict.Fail
        'a pass, then a fail' | '{"passed": true} Actually, on reflection: {"passed": false, "findings": ["x"]}' || Verdict.Pass
        'a fail, then a pass' | 'Draft: {"passed": false, "findings": ["x"]} Final: {"passed": true}' || Verdict.Fail
    }
}
