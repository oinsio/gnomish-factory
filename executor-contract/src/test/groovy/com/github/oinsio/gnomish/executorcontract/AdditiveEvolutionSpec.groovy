package com.github.oinsio.gnomish.executorcontract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.node.ObjectNode
import spock.lang.Specification

/**
 * FR4, NFR-R3 of define-executor-contract: the contract evolves additively. Through the one binding,
 * {@link ExecutorContractJson}, a document carrying fields its reader does not know — at the top and
 * in every nested object — reads as the same value without them, and an optional field the writer
 * has no value for is left out of the written document. A required number the document lacks, or
 * spells as {@code null}, fails the read rather than reading as {@code 0} or {@code false}.
 */
class AdditiveEvolutionSpec extends Specification {

    /**
     * Objects the contract carries as written — the law's settings, the settings schema, the result's
     * unread answer: a field added there is data, not an unknown field.
     */
    private static final Set<String> CARRIED = [
        'settings',
        'settingsSchema',
        'answer'
    ] as Set

    // Scenario: executor/wire-contract / Newer executor field is ignored
    def "FR4: a #type.simpleName carrying fields this factory does not know reads without them"() {
        expect:
        samples.every { sample ->
            JsonNode written = WireJson.valueToTree(sample)
            assert WireJson.treeToValue(widened(written), type) == sample
            true
        }

        where:
        type | samples
        ExecutorResult | WireSamples.results()
        Describe | WireSamples.describes()
        Answer | WireSamples.answers()
    }

    def "FR4: an executor built against an older contract reads a request carrying a field added since"() {
        expect:
        WireSamples.requests().every { request ->
            assert WireJson.treeToValue(widened(WireJson.valueToTree(request)), ExecutorRequest) == request
            true
        }
    }

    def "FR4: a request whose optional continuation is unset is written without the field"() {
        given:
        def request = WireSamples.request(Role.TRANSFORM, RoleInputs.transform('Implement it.'))

        when:
        def written = WireJson.valueToTree(request)

        then:
        !written.has('continuation')
        WireJson.treeToValue(written, ExecutorRequest) == request
    }

    def "NFR-R3: a #type.simpleName lacking the required #field is refused, never read as zero or false"() {
        when:
        WireJson.treeToValue(without(WireJson.valueToTree(sample), field), type)

        then:
        def refusal = thrown(MismatchedInputException)
        refusal.message.contains(field)

        where:
        type | sample | field
        Describe | WireSamples.describes()[0] | 'protocolVersion'
        Describe | WireSamples.describes()[0] | 'reportsUsage'
        ModelUsage | new ModelUsage(1, 2, 3, 4, null) | 'input'
        ModelUsage | new ModelUsage(1, 2, 3, 4, null) | 'output'
        ModelUsage | new ModelUsage(1, 2, 3, 4, null) | 'cacheCreation'
        ModelUsage | new ModelUsage(1, 2, 3, 4, null) | 'cacheRead'
        ToolUsage | new ToolUsage('Edit', 7, null) | 'calls'
    }

    def "NFR-R3: a null for the required #field is refused"() {
        when:
        WireJson.readValue(json, type)

        then:
        thrown(MismatchedInputException)

        where:
        type | field | json
        ModelUsage | 'input' | '{"input":null,"output":0,"cacheCreation":0,"cacheRead":0}'
        ToolUsage | 'calls' | '{"name":"Edit","calls":null}'
    }

    def "NFR-R3: a usage whose model lacks a token count fails the result's read instead of reporting zero"() {
        when:
        WireJson.readValue('{"status":"completed","usage":{"provenance":"REPORTED","models":{"m":' +
                '{"output":1,"cacheCreation":0,"cacheRead":0}}}}', ExecutorResult)

        then:
        thrown(MismatchedInputException)
    }

    def "NFR-R3: the optional numbers stay optional"() {
        expect:
        WireJson.readValue('{"input":1,"output":2,"cacheCreation":3,"cacheRead":4}', ModelUsage) ==
                new ModelUsage(1, 2, 3, 4, null)
        WireJson.readValue('{"name":"Edit","calls":7}', ToolUsage) == new ToolUsage('Edit', 7, null)
        WireJson.readValue('{"provenance":"REPORTED"}', Usage) == new Usage(UsageProvenance.REPORTED, null, [:], [])
    }

    def "FR8: the mirror interface is describe, start and poll over the contract's DTOs"() {
        expect:
        Executor.isInterface()
        Executor.declaredMethods.collect {
            "${it.name}(${it.parameterTypes*.simpleName.join(',')}):${it.returnType.simpleName}"
        }
        .toSet() == [
            'describe():Describe',
            'start(ExecutorRequest):ExecutorResult',
            'poll(ExecutorRequest,PendingKey):ExecutorResult'
        ] as Set
    }

    /** A deep copy of {@code node} with an unknown field added to every object the contract reads. */
    private static JsonNode widened(JsonNode node) {
        JsonNode copy = node.deepCopy()
        widen(copy)
        copy
    }

    private static void widen(JsonNode node) {
        if (node.isObject()) {
            def object = (ObjectNode) node
            object.properties().each { entry ->
                if (entry.key == 'models') {
                    // a model id is a key of the map, not a field: widen each model's usage instead
                    entry.value.each { widen(it) }
                } else if (!(entry.key in CARRIED)) {
                    widen(entry.value)
                }
            }
            object.putObject('addedInALaterVersion').put('hint', 1)
        } else if (node.isArray()) {
            node.each { widen(it) }
        }
    }

    private static JsonNode without(JsonNode node, String field) {
        ObjectNode copy = node.deepCopy()
        copy.remove(field)
        copy
    }
}
