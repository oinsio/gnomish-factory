package com.github.oinsio.gnomish.executorcontract

import static com.github.oinsio.gnomish.executorcontract.WireJson.t

import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR5 of migrate-verify-to-executor-contract: a bare SARIF log — the file a tool wrote, not an
 * answer document around it — reads as the runs the same log states inside a verdict answer, not
 * yet counted against the verdict's result cap (the relaying executor caps them), and every refusal
 * of that reading is a {@code Malformed} with its reason, minted in the family the caller names.
 */
class SarifLogReadSpec extends Specification {

    private static SarifLogReading readLog(String json) {
        Answer.readSarif(WireJson.readTree(json), UntrustedText.&agent)
    }

    def "FR5: a bare SARIF log reads as the runs the same log states inside a verdict answer"() {
        given:
        def log = '{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"lint"}},"results":[' +
                '{"ruleId":"r","level":"error","message":{"text":"m"},"locations":[{"physicalLocation":' +
                '{"artifactLocation":{"uri":"src/A.java"},"region":{"startLine":4}}}]}]}]}'

        expect:
        readLog(log) == new SarifLogReading.Parsed([
            new SarifRun(t('lint'), [
                new SarifResult(t('r'), SarifLevel.ERROR, t('m'), t('src/A.java'), 4)
            ], null)
        ])
        WireJson.answer(WireJson.readTree('{"kind":"verdict","sarif":' + log + '}')) ==
                new AnswerReading.Parsed(new Answer.Verdict((readLog(log) as SarifLogReading.Parsed).runs()))
    }

    def "FR5: a bare SARIF log over the verdict's result cap is read whole, for the relaying executor to cap"() {
        given:
        def results = (1..(Answer.Verdict.MAX_RESULTS + 1)).collect {
            '{"message":{"text":"m"}}'
        }.join(',')
        def log = '{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"lint"}},"results":[' + results + ']}]}'

        expect:
        (readLog(log) as SarifLogReading.Parsed).runs()[0].results().size() == Answer.Verdict.MAX_RESULTS + 1
    }

    def "FR5: a log the verdict reading refuses reads as Malformed with the reason '#reason'"() {
        expect:
        readLog(json) == new SarifLogReading.Malformed(t(reason))

        where:
        json || reason
        '[]' || 'the verdict answer has no SARIF log'
        '{"version":"2.0.0","runs":[]}' || "the SARIF log is version '2.0.0'; only 2.1.0 is read"
    }

    def "FR5: the reason of a malformed log is minted in the caller's family"() {
        when:
        def reading = Answer.readSarif(WireJson.readTree('{"runs":[]}'), UntrustedText.&subprocess)

        then:
        reading instanceof SarifLogReading.Malformed
        (reading as SarifLogReading.Malformed).reason().provenance() == Provenance.SUBPROCESS
    }
}
