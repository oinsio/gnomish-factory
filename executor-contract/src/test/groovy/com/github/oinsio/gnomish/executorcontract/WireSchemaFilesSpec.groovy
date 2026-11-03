package com.github.oinsio.gnomish.executorcontract

import com.fasterxml.jackson.databind.JsonNode
import spock.lang.Specification

/**
 * FR1 of define-executor-contract: the executor contract's four JSON Schema files ship on the
 * module's classpath, each one document of one draft with its own identity and examples. The
 * schema-against-DTO identity is {@code WireSchemaRoundTripSpec}'s (task 2.2); this spec pins only
 * what that identity loads. Every file is read through its one loader, {@link WireSchemas} (FR24,
 * design D19), so the files pinned here are the files the conformance kit judges with.
 */
class WireSchemaFilesSpec extends Specification {

    private static final String DRAFT = 'https://json-schema.org/draft/2020-12/schema'

    private static final String ID_BASE = 'https://github.com/oinsio/gnomish-factory/schemas/executor-contract/1/'

    def "FR1: the #kind schema is #file under schemas/, of draft 2020-12, with an \$id naming it"() {
        when:
        def schema = WireSchemas.document(kind)

        then:
        kind.schemaFile() == file
        schema.path('$schema').asText() == DRAFT
        schema.path('$id').asText() == ID_BASE + file
        schema.path('type').asText() == 'object'

        where:
        kind | file
        WireDocument.REQUEST | 'executor-request.schema.json'
        WireDocument.RESULT | 'executor-result.schema.json'
        WireDocument.ANSWER | 'executor-answer.schema.json'
        WireDocument.DESCRIBE | 'executor-describe.schema.json'
    }

    def "FR1: every example of the #kind schema carries every property the schema requires"() {
        given:
        def schema = WireSchemas.document(kind)
        def required = schema.path('required')*.asText()

        expect:
        schema.path('examples').size() >= 2
        schema.path('examples').every { JsonNode example ->
            example.isObject() && required.every { example.has(it) }
        }

        where:
        kind << WireDocument.values()
    }

    def "FR4: no schema closes its objects, so a reader ignores what it does not know"() {
        expect:
        WireDocument.values().findAll {
            closesAnObject(WireSchemas.document(it))
        } == []
    }

    private static boolean closesAnObject(JsonNode node) {
        if ([
                    'additionalProperties',
                    'unevaluatedProperties'
                ].any {
                    node.path(it).isBoolean() && !node.path(it).asBoolean()
                }) {
            return true
        }
        node.elements().any { closesAnObject(it) }
    }
}
