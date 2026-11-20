package com.github.oinsio.gnomish.executorcontract

import static com.github.oinsio.gnomish.executorcontract.WireJson.t

import spock.lang.Specification

/**
 * FR5, FR6 of migrate-verify-to-executor-contract (design D19): details travel apart from the
 * message. A result carries {@code details} beside its {@code reason} on any status, and keeps it
 * through every arm of its reading; a SARIF result carries its own as {@code properties.details},
 * read when it is a string and ignored, never malformed, when it is anything else.
 */
class ResultDetailsReadSpec extends Specification {

    private static final String RESULT = '{"kind":"verdict","sarif":{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"lint"}},"results":['

    def "FR5: a #status result carries its details beside its reason, as written"() {
        when:
        def result = WireJson.readValue(json, ExecutorResult)

        then:
        result.status() == status
        result.reason() == t('r')
        result.details() == t('the tail')

        and: 'the schema accepts details on every status'
        WireSchemas.judge(WireDocument.RESULT, json).isEmpty()

        where:
        status | json
        Status.COMPLETED | '{"status":"completed","reason":"r","details":"the tail","usage":{"provenance":"ABSENT"}}'
        Status.FAILED | '{"status":"failed","failureClass":"quality","reason":"r","details":"the tail","usage":{"provenance":"ABSENT"}}'
        Status.PENDING | '{"status":"pending","pendingKey":"k","reason":"r","details":"the tail","usage":{"provenance":"ABSENT"}}'
    }

    def "FR5: a result that fails closed keeps the details it was written with"() {
        expect:
        WireJson.readValue('{"status":"done","details":"the tail"}', ExecutorResult).details() == t('the tail')
        WireJson.readValue('{"status":"completed","pendingState":"running","details":"the tail"}', ExecutorResult)
                .details() == t('the tail')
    }

    def "FR5: the schema refuses details that are no string"() {
        expect:
        WireSchemas.judge(WireDocument.RESULT, '{"status":"completed","details":7,"usage":{"provenance":"ABSENT"}}')*.path
                .contains('details')
    }

    def "FR5: a string properties.details reads into the SARIF result's details, its message unchanged"() {
        when:
        def reading = WireJson.answer(WireJson.readTree(RESULT +
                '{"level":"error","message":{"text":"exited 1"},"properties":{"details":"the tail","tags":["x"]}}]}]}}'))

        then:
        reading == new AnswerReading.Parsed(new Answer.Verdict([
            new SarifRun(t('lint'), [
                new SarifResult(null, SarifLevel.ERROR, t('exited 1'), null, null, t('the tail'))
            ], null)
        ]))
    }

    // Scenario: executor/wire-contract / A third-party details property of another type is ignored
    def "FR5: a properties.details that is #shape is ignored, and the answer reads as a verdict whose result has no details"() {
        given:
        def json = RESULT + '{"level":"error","message":{"text":"m"},"properties":{"details":' + details + '}}]}]}}'

        when:
        def reading = WireJson.answer(WireJson.readTree(json))

        then:
        reading instanceof AnswerReading.Parsed
        def result = ((reading as AnswerReading.Parsed).answer() as Answer.Verdict).runs()[0].results()[0]
        result.details() == null
        result.message() == t('m')

        and: 'the schema leaves the property bag open as well'
        WireSchemas.judge(WireDocument.ANSWER, json).isEmpty()

        where:
        shape | details
        'an object' | '{"text":"x"}'
        'a number' | '7'
        'an array' | '["x"]'
        'null' | 'null'
    }

    def "FR5: a SARIF result's details are written as its properties.details and read back as themselves"() {
        given:
        def verdict = new Answer.Verdict([
            new SarifRun(t('lint'), [
                new SarifResult(null, SarifLevel.ERROR, t('exited 1'), null, null, t('the tail')),
                new SarifResult(null, SarifLevel.NOTE, t('fine'), null, null)
            ], null)
        ])

        when:
        def tree = ExecutorContractJson.answerTree(verdict)

        then:
        tree.path('sarif').path('runs')[0].path('results')[0].path('properties').path('details').asText() == 'the tail'
        !tree.path('sarif').path('runs')[0].path('results')[1].has('properties')
        WireJson.answer(tree) == new AnswerReading.Parsed(verdict)
    }
}
