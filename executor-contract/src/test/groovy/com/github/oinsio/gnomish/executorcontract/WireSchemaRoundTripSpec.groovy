package com.github.oinsio.gnomish.executorcontract

import com.fasterxml.jackson.databind.JsonNode
import spock.lang.Specification

/**
 * FR1, NFR-R3 of define-executor-contract — the D10 identity "Java DTOs and JSON schema are one":
 * every DTO sample serializes to a document its schema file accepts, and every example a schema file
 * carries reads into its DTO and writes back as the same document, still accepted. The schemas are
 * the ones {@link WireSchemas} loads and judges with (FR24, design D19), so the identity holds for the
 * files the conformance kit judges a candidate against. The answer is carried unread by the result and has its own row: its samples and
 * examples go through {@link Answer}'s reading and writing.
 */
class WireSchemaRoundTripSpec extends Specification {

    def "FR1: a #type.simpleName sample writes a document the #kind schema accepts"() {
        expect:
        samples.every { sample ->
            def errors = WireSchemas.judge(kind, WireJson.writeValueAsString(sample))
            assert errors.isEmpty(): "${sample} -> ${errors}"
            true
        }

        where:
        type | kind | samples
        ExecutorRequest | WireDocument.REQUEST | WireSamples.requests()
        ExecutorResult | WireDocument.RESULT | WireSamples.results()
        Answer | WireDocument.ANSWER | WireSamples.answers()
        Describe | WireDocument.DESCRIBE | WireSamples.describes()
    }

    def "NFR-R3: every example of the #kind schema reads into #type.simpleName and writes back as itself"() {
        given:
        def examples = WireSchemas.document(kind).path('examples')

        expect:
        examples.size() >= 2
        examples.every { JsonNode example ->
            def again = WireJson.writeValueAsString(WireJson.treeToValue(example, type))
            assert WireJson.readTree(again) == example
            assert WireSchemas.judge(kind, again).isEmpty()
            true
        }

        where:
        type | kind
        ExecutorRequest | WireDocument.REQUEST
        ExecutorResult | WireDocument.RESULT
        Answer | WireDocument.ANSWER
        Describe | WireDocument.DESCRIBE
    }

    // Scenario: executor/wire-contract / Role selects the inputs, not the shape
    def "FR1: a verdict and a transform request validate against one schema and differ only in role and role inputs"() {
        given:
        def transform = written(WireSamples.request(Role.TRANSFORM, RoleInputs.transform('Implement it.')))
        def verdict = written(WireSamples.request(Role.VERDICT, RoleInputs.verdict('Every method has a javadoc.')))

        expect:
        WireSchemas.judge(WireDocument.REQUEST, transform.toString()).isEmpty()
        WireSchemas.judge(WireDocument.REQUEST, verdict.toString()).isEmpty()
        differingFields(transform, verdict) == ['role', 'roleInputs'] as Set
        transform.path('roleInputs').fieldNames().toList() == ['instructions']
        verdict.path('roleInputs').fieldNames().toList() == ['criteria']
    }

    def "FR2: a result whose status the taxonomy lacks is refused by the schema, so only the reader's arm admits it"() {
        expect:
        !WireSchemas.judge(WireDocument.RESULT, '{"status":"done","usage":{"provenance":"ABSENT"}}').isEmpty()
    }

    def "FR3: a decision request with one option and a non-SARIF verdict are refused by the schema as by the reader"() {
        expect:
        !WireSchemas.judge(WireDocument.ANSWER, json).isEmpty()
        WireJson.answer(WireJson.readTree(json)) instanceof AnswerReading.Malformed

        where:
        json << [
            '{"kind":"decision-request","question":"q?","options":["only"]}',
            '{"kind":"verdict","sarif":{"version":"2.0.0","runs":[]}}'
        ]
    }

    private static JsonNode written(Object dto) {
        WireJson.readTree(WireJson.writeValueAsString(dto))
    }

    private static Set<String> differingFields(JsonNode left, JsonNode right) {
        (left.fieldNames().toList() + right.fieldNames().toList()).findAll {
            left.get(it) != right.get(it)
        } as Set
    }
}
