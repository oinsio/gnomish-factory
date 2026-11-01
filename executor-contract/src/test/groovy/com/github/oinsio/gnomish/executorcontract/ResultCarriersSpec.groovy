package com.github.oinsio.gnomish.executorcontract

import static com.github.oinsio.gnomish.executorcontract.WireJson.t

import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * Every text field an executor writes leaves the result reader as a carrier in the family of the
 * file it came from (FR6, NFR-S2 of define-executor-contract): the result's reason and denials, the
 * describe's version, and every text of an answer — read in the result file or in an answer file.
 */
class ResultCarriersSpec extends Specification {

    def reader = new ResultFileReader(1024 * 1024, UntrustedText.&container)

    private <T> T parsed(String json, Class<T> type) {
        def result = reader.readJson(CannedSource.of(json), type)
        assert result instanceof ReadResult.Parsed
        (T) result.value()
    }

    def "NFR-S2: a result's reason and every denial text are carriers of the file's family"() {
        when:
        def result = parsed('{"status":"completed","reason":"done","usage":{"provenance":"ABSENT"},' +
                '"denials":[{"message":"m","location":"l","details":"d"}]}', ExecutorResult)
        def denial = result.denials()[0]

        then:
        result.reason() == t('done')
        denial == new Denial(t('m'), t('l'), t('d'))
        [
            result.reason(),
            denial.message(),
            denial.location(),
            denial.details()
        ]*.provenance()
        .every { it == Provenance.CONTAINER }
    }

    def "NFR-S2: the describe's version is a carrier; its names stay validated strings"() {
        when:
        def describe = parsed('{"protocolVersion":1,"version":"0.1","roles":[],"capabilities":[],"settingsSchema":{},' +
                '"credentials":["ACME_KEY"],"egress":["api.example"],"policyDisablingFlags":["--x"],' +
                '"enforcementLevel":"OS_SANDBOX","reportsUsage":false}', Describe)

        then:
        describe.version().provenance() == Provenance.CONTAINER
        describe.credentials() == ['ACME_KEY']
    }

    def "NFR-S2: an answer file's texts are carriers of the file's family"() {
        when:
        def request = parsed('{"kind":"decision-request","question":"q?","options":["a","b"],"continuationKey":"k"}',
                Answer) as Answer.DecisionRequest

        then:
        request == new Answer.DecisionRequest(t('q?'), [t('a'), t('b')], 'k')
        ([request.question()] + request.options())*.provenance().every {
            it == Provenance.CONTAINER
        }
    }

    def "NFR-S2: the answer slot read by Answer.read mints in the family it is handed"() {
        given:
        def result = parsed('{"status":"completed","usage":{"provenance":"ABSENT"},"answer":{"kind":"verdict",' +
                '"sarif":{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"j"}},"results":[{"ruleId":"r",' +
                '"message":{"text":"m"},"locations":[{"physicalLocation":{"artifactLocation":{"uri":"u"}}}]}]}]}}}',
                ExecutorResult)

        when:
        def verdict = (Answer.read(result.answer(), UntrustedText.&container) as AnswerReading.Parsed).answer() as Answer.Verdict
        def run = verdict.runs()[0]
        def finding = run.results()[0]

        then:
        [
            run.tool(),
            finding.ruleId(),
            finding.message(),
            finding.uri()
        ]*.provenance().every {
            it == Provenance.CONTAINER
        }
    }

    def "NFR-S2: the reason of a malformed answer is minted in the family it is handed"() {
        when:
        def reading = Answer.read(WireJson.readTree('{"kind":"poll"}'), UntrustedText.&container) as AnswerReading.Malformed

        then:
        reading.reason() == t("unknown answer kind 'poll'")
        reading.reason().provenance() == Provenance.CONTAINER
    }

    def "FR6: a malformed answer file is a malformed read, never an exception"() {
        when:
        def result = reader.readJson(CannedSource.of('{"kind":"select","chosen":" "}'), Answer)

        then:
        result instanceof ReadResult.Malformed
        result.reason().raw().contains('malformed answer: select.chosen must not be blank')
    }

    def "NFR-S2: a composed reason is the factory's, quoting the unread token and the executor's reason inertly"() {
        when:
        def result = parsed('{"status":"do\\u001Bne","reason":"x\\u001By","usage":{"provenance":"ABSENT"}}', ExecutorResult)

        then:
        result.reason().provenance() == Provenance.FACTORY
        !result.reason().raw().contains('\u001B')
        result.reason().raw().startsWith('unknown status \'do')
    }

    def "NFR-S2: an unread token is quoted up to the quote cap"() {
        when:
        def result = parsed('{"status":"' + 'z' * 300 + '","usage":{"provenance":"ABSENT"}}', ExecutorResult)

        then:
        result.reason().raw().length() < 300
        ResultReading.TOKEN_QUOTE_CAP_CHARS == 200
    }
}
