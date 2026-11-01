package com.github.oinsio.gnomish.executorcontract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.oinsio.gnomish.untrustedtext.Provenance
import spock.lang.Specification

/**
 * FR24, FR1 of define-executor-contract (design D19): {@link WireSchemas} judges the raw text of a
 * document against its schema file, strictly — an enum outside the set and a missing required field
 * are refused although the lenient readers would have read past them — and accepts every example the
 * file carries. Each break document is the schema's own first example with one change.
 */
class WireSchemasSpec extends Specification {

    def "FR24: every example of the #kind schema is accepted"() {
        given:
        def examples = WireSchemas.document(kind).path('examples')

        expect:
        examples.size() >= 2
        examples.every { WireSchemas.judge(kind, it.toString()).isEmpty() }

        where:
        kind << WireDocument.values()
    }

    def "FR24: the #kind schema refuses #field #change with the #keyword keyword at its path"() {
        given:
        ObjectNode document = firstExample(kind)
        breakIt(document)

        when:
        def violations = WireSchemas.judge(kind, document.toString())

        then:
        violations*.path.contains(field)
        violations.find { it.path == field }.keyword == keyword
        violations.every {
            it.message.provenance() == Provenance.SUBPROCESS && !it.message.isBlank()
        }

        where:
        kind | field | change | keyword | breakIt
        WireDocument.DESCRIBE | 'enforcementLevel' | 'outside its enum' | 'enum' | { ObjectNode d ->
            d.put('enforcementLevel', 'SANDBOX_MAYBE')
        }
        WireDocument.DESCRIBE | 'roles' | 'missing' | 'required' | { ObjectNode d ->
            d.remove('roles')
        }
        WireDocument.REQUEST | 'role' | 'outside its enum' | 'enum' | { ObjectNode d ->
            d.put('role', 'effectful')
        }
        WireDocument.REQUEST | 'workspace' | 'missing' | 'required' | { ObjectNode d ->
            d.remove('workspace')
        }
        WireDocument.RESULT | 'status' | 'outside its enum' | 'enum' | { ObjectNode d ->
            d.put('status', 'done')
        }
        WireDocument.RESULT | 'usage' | 'missing' | 'required' | { ObjectNode d ->
            d.remove('usage')
        }
        WireDocument.ANSWER | 'kind' | 'missing' | 'required' | { ObjectNode d ->
            d.remove('kind')
        }
    }

    def "FR24: an answer whose kind is outside the set is refused"() {
        given:
        ObjectNode answer = firstExample(WireDocument.ANSWER)
        answer.put('kind', 'essay')

        expect:
        !WireSchemas.judge(WireDocument.ANSWER, answer.toString()).isEmpty()
    }

    def "FR24: a result's answer is judged by the loaded answer schema through the result's \$ref"() {
        given:
        ObjectNode result = firstExample(WireDocument.RESULT)
        result.set('answer', WireJson.readTree('{"kind":"essay"}'))

        when:
        def violations = WireSchemas.judge(WireDocument.RESULT, result.toString())

        then:
        !violations.isEmpty()
        violations.every { it.path.startsWith('answer') }
    }

    def "FR24: a field the request schema does not define is no violation, since no schema closes its objects"() {
        given:
        ObjectNode request = firstExample(WireDocument.REQUEST)
        request.put('x-gnomish-conformance-probe', true)

        expect:
        WireSchemas.judge(WireDocument.REQUEST, request.toString()).isEmpty()
    }

    def "FR24: a text that is no single JSON value (#shape) is one violation of the json keyword"() {
        when:
        def violations = WireSchemas.judge(WireDocument.RESULT, text)

        then:
        violations.size() == 1
        violations[0].path == ''
        violations[0].keyword == WireViolation.NOT_JSON
        violations[0].message.provenance() == provenance
        !violations[0].message.isBlank()

        where:
        shape | text || provenance
        'empty' | '' || Provenance.FACTORY
        'blank' | '  \n' || Provenance.FACTORY
        'cut' | '{"status":' || Provenance.SUBPROCESS
        'followed by more' | '{"status":"completed","usage":{"provenance":"ABSENT"}} {}' || Provenance.SUBPROCESS
    }

    def "FR24: a JSON value that is no object is judged by the schema, not refused as unreadable"() {
        when:
        def violations = WireSchemas.judge(WireDocument.DESCRIBE, 'null')

        then:
        violations*.keyword == ['type']
        violations[0].path == ''
    }

    def "FR1: the schema document handed out is a copy, so a caller's change reaches no later reader"() {
        given:
        ObjectNode first = WireSchemas.document(WireDocument.DESCRIBE) as ObjectNode
        first.removeAll()

        expect:
        WireSchemas.document(WireDocument.DESCRIBE).path('$id').asText().endsWith('executor-describe.schema.json')
    }

    private static ObjectNode firstExample(WireDocument kind) {
        JsonNode example = WireSchemas.document(kind).path('examples').get(0)
        example.deepCopy() as ObjectNode
    }
}
