package com.github.oinsio.gnomish.executorcontract

import static com.github.oinsio.gnomish.executorcontract.WireJson.t

import spock.lang.Specification

/**
 * FR6 of migrate-verify-to-executor-contract (design D23): a SARIF run carries the URL of the
 * platform run that produced it as its {@code properties.runUrl}, read when a non-blank string and
 * ignored, never malformed, when blank or of another type; the writer puts it back where the reader
 * takes it from.
 */
class RunUrlReadSpec extends Specification {

    private static final String LOG = '{"kind":"verdict","sarif":{"version":"2.1.0","runs":['

    private static String run(String properties) {
        '{"tool":{"driver":{"name":"ci"}},"results":[]' + (properties == null ? '' : ',"properties":' + properties) + '}'
    }

    private static List<SarifRun> runs(String json) {
        def reading = WireJson.answer(WireJson.readTree(json))
        assert reading instanceof AnswerReading.Parsed
        ((reading as AnswerReading.Parsed).answer() as Answer.Verdict).runs()
    }

    def "FR6: a string properties.runUrl reads into the run's URL, minted in the writer's family"() {
        when:
        def read = runs(LOG + run('{"runUrl":"https://ci/runs/1","tags":["x"]}') + ']}}')

        then:
        read == [
            new SarifRun(t('ci'), [], t('https://ci/runs/1'))
        ]
        read[0].runUrl().provenance() == t('x').provenance()
    }

    def "FR6: a run without the property carries no URL"() {
        expect:
        runs(LOG + run(null) + ']}}')*.runUrl() == [null]
        runs(LOG + run('{}') + ']}}')*.runUrl() == [null]
    }

    // Scenario: executor/wire-contract / A malformed run URL is ignored
    def "FR6: a properties.runUrl that is #shape is ignored, and the answer reads as a verdict whose run has no URL"() {
        given:
        def json = LOG + run('{"runUrl":' + value + '}') + ']}}'

        expect:
        runs(json)*.runUrl() == [null]

        and: 'the schema leaves the run URL untyped as well'
        WireSchemas.judge(WireDocument.ANSWER, json).isEmpty()

        where:
        shape | value
        'an object' | '{"href":"https://ci/runs/1"}'
        'a number' | '7'
        'an array' | '["https://ci/runs/1"]'
        'null' | 'null'
        'blank' | '"  "'
        'empty' | '""'
    }

    def "FR6: each run keeps its own URL, in the order written"() {
        expect:
        runs(LOG + run('{"runUrl":"https://ci/1"}') + ',' + run(null) + ',' + run('{"runUrl":"https://ci/3"}') + ']}}')
                *.runUrl() == [
                    t('https://ci/1'),
                    null,
                    t('https://ci/3')
                ]
    }

    def "FR6: a run's URL is written as its properties.runUrl and read back as itself; a run without one writes no property bag"() {
        given:
        def verdict = new Answer.Verdict([
            new SarifRun(t('ci'), [], t('https://ci/runs/1')),
            new SarifRun(t('lint'), [], null)
        ])

        when:
        def tree = ExecutorContractJson.answerTree(verdict)

        then:
        tree.path('sarif').path('runs')[0].path('properties').path('runUrl').asText() == 'https://ci/runs/1'
        !tree.path('sarif').path('runs')[1].has('properties')
        WireJson.answer(tree) == new AnswerReading.Parsed(verdict)
        WireSchemas.judge(WireDocument.ANSWER, tree.toString()).isEmpty()
    }
}
