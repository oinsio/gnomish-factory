package com.github.oinsio.gnomish.executorcontract

import static com.github.oinsio.gnomish.executorcontract.WireJson.t

import com.fasterxml.jackson.databind.JsonMappingException
import spock.lang.Specification

/**
 * FR3, NFR-S2 of define-executor-contract: an answer document reads as its typed kind, and every
 * malformed one — a decision request with one option, a verdict that is not SARIF 2.1.0, a field
 * of the wrong type, an unknown kind — reads as {@code Malformed} with a reason naming what is wrong,
 * never as an answer and never as a pass.
 */
class AnswerReadSpec extends Specification {

    private static AnswerReading read(String json) {
        WireJson.answer(WireJson.readTree(json))
    }

    private static String verdict(String result, String version = '"2.1.0"') {
        """{"kind":"verdict","sarif":{"version":${version},"runs":[{"tool":{"driver":{"name":"j"}},"results":[${result}]}]}}"""
    }

    def "FR3: #json reads as #expected"() {
        expect:
        read(json) == new AnswerReading.Parsed(expected)

        where:
        json || expected
        '{"kind":"select","chosen":"main","ranking":["main","dev"],"rationale":"why"}' || new Answer.Select(t('main'), [t('main'), t('dev')], t('why'))
        '{"kind":"select","chosen":"main","ranking":null}' || new Answer.Select(t('main'), [], null)
        '{"kind":"decision-request","question":"q?","options":["a","b"],"continuationKey":"k"}' || new Answer.DecisionRequest(t('q?'), [t('a'), t('b')], 'k')
        '{"kind":"decision-request","question":"q?","options":["a","b"],"future":1}' || new Answer.DecisionRequest(t('q?'), [t('a'), t('b')], null)
        verdict('{"message":{"text":"m"}}') || new Answer.Verdict([
            new SarifRun(t('j'), [
                new SarifResult(null, SarifLevel.WARNING, t('m'), null, null)
            ])
        ])
        verdict('''{"ruleId":"r","level":"error","message":{"text":"m"},"locations":[{"physicalLocation":
            {"artifactLocation":{"uri":"src/Foo.java"},"region":{"startLine":12}}}],"fingerprints":{}}''') || new Answer.Verdict([
                    new SarifRun(t('j'), [
                        new SarifResult(t('r'), SarifLevel.ERROR, t('m'), t('src/Foo.java'), 12)
                    ])
                ])
        verdict('{"level":"note","message":{"text":"m"},"locations":[{"physicalLocation":{"region":{"startLine":3}}}]}') || new Answer.Verdict([
            new SarifRun(t('j'), [
                new SarifResult(null, SarifLevel.NOTE, t('m'), null, 3)
            ])
        ])
        verdict('{"message":{"text":"m"},"locations":[{"physicalLocation":{"region":{"startLine":1}}}]}') || new Answer.Verdict([
            new SarifRun(t('j'), [
                new SarifResult(null, SarifLevel.WARNING, t('m'), null, 1)
            ])
        ])
        '{"kind":"verdict","sarif":{"version":"2.1.0","runs":[]}}' || new Answer.Verdict([])
    }

    def "FR3: every sample answer writes a document that reads back as the same answer"() {
        expect:
        WireSamples.answers().every { answer ->
            def again = WireJson.readValue(WireJson.writeValueAsString(answer), Answer)
            assert again == answer
            assert WireJson.answer(WireJson.valueToTree(answer)) == new AnswerReading.Parsed(answer)
            true
        }
    }

    def "FR3: a malformed answer #json reads as Malformed with the reason '#reason'"() {
        expect:
        read(json) == new AnswerReading.Malformed(t(reason))

        where:
        json || reason
        '"text"' || 'the answer is not a JSON object'
        '{"chosen":"main"}' || 'the answer has no string kind'
        '{"kind":7}' || 'the answer has no string kind'
        '{"kind":"poll"}' || "unknown answer kind 'poll'"
        '{"kind":"decision-request","question":"q?","options":["only"]}' || 'decision-request.options holds 1 items; at least 2 are required, 1 missing'
        '{"kind":"decision-request","question":"q?"}' || 'decision-request.options holds 0 items; at least 2 are required, 2 missing'
        '{"kind":"decision-request","question":"q?","options":"a,b"}' || 'the decision-request answer has no array options'
        '{"kind":"decision-request","question":"q?","options":["a",2]}' || 'the decision-request answer has a non-string item in options'
        '{"kind":"decision-request","question":" ","options":["a","b"]}' || 'decision-request.question must not be blank'
        '{"kind":"decision-request","question":"q","options":["a","b"],"continuationKey":""}' || 'decision-request.continuationKey must not be blank'
        '{"kind":"select"}' || 'the select answer has no string chosen'
        '{"kind":"select","chosen":"m","rationale":5}' || 'the select answer has no string rationale'
        '{"kind":"select","chosen":"m","ranking":[""]}' || 'select.ranking[] must not be blank'
        '{"kind":"verdict"}' || 'the verdict answer has no SARIF log'
        '{"kind":"verdict","sarif":"<xml/>"}' || 'the verdict answer has no SARIF log'
        '{"kind":"verdict","sarif":{"runs":[]}}' || 'the SARIF log has no string version'
        '{"kind":"verdict","sarif":{"version":"2.0.0","runs":[]}}' || "the SARIF log is version '2.0.0'; only 2.1.0 is read"
        '{"kind":"verdict","sarif":{"version":"2.1.0"}}' || 'the SARIF log has no array runs'
        '{"kind":"verdict","sarif":{"version":"2.1.0","runs":[{"results":[]}]}}' || "a SARIF run's tool.driver has no string name"
        '{"kind":"verdict","sarif":{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"j"}}}]}}' || 'a SARIF run has no array results'
        verdict('{"level":"error"}') || "a SARIF result's message has no string text"
        verdict('{"level":"fatal","message":{"text":"m"}}') || "unknown SARIF level 'fatal'"
        verdict('{"level":3,"message":{"text":"m"}}') || 'a SARIF result has no string level'
        verdict('{"message":{"text":"m"},"locations":[{"physicalLocation":{"region":{"startLine":"7"}}}]}') || "a SARIF region's startLine is not an integer"
        verdict('{"message":{"text":"m"},"locations":[{"physicalLocation":{"region":{"startLine":0}}}]}') || 'sarif result startLine must be at least 1, was 0'
    }

    def "FR3: a verdict of #count results is #outcome: the cap refuses the whole log rather than cutting it"() {
        given:
        def results = (1..count).collect {
            '{"level":"error","message":{"text":"m"}}'
        }.join(',')

        expect:
        read(verdict(results)).getClass() == outcome

        where:
        count || outcome
        Answer.Verdict.MAX_RESULTS || AnswerReading.Parsed
        Answer.Verdict.MAX_RESULTS + 1 || AnswerReading.Malformed
    }

    def "FR3: the cap counts the results of every run together"() {
        given:
        def run = new SarifRun(t('j'), (1..600).collect {
            new SarifResult(null, SarifLevel.NOTE, t('m'), null, null)
        })

        when:
        new Answer.Verdict([run, run])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == 'the verdict carries 1200 SARIF results; at most 1000 are read'
    }

    def "FR3: Jackson reads an answer through the same reading, and refuses a malformed one by name"() {
        expect:
        WireJson.readValue('{"kind":"select","chosen":"main"}', Answer) == new Answer.Select(t('main'), [], null)

        when:
        WireJson.readValue('{"kind":"decision-request","question":"q?","options":["only"]}', Answer)

        then:
        def refused = thrown(JsonMappingException)
        refused.originalMessage == 'malformed answer: decision-request.options holds 1 items; at least 2 are required, 1 missing'
    }
}
