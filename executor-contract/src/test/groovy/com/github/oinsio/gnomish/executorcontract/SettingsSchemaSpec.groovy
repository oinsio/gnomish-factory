package com.github.oinsio.gnomish.executorcontract

import spock.lang.Specification

/**
 * The one judge of a law's settings against a describe-declared schema (FR9, FR12, UX1, NFR-S2 of
 * define-executor-contract): each violation located inside the settings and named by its rule, and
 * a schema that would make the factory fetch, or that cannot compile, judged unusable.
 */
class SettingsSchemaSpec extends Specification {

    private static final String STRICT = '''{"type":"object","additionalProperties":false,
        "properties":{"maxTurns":{"type":"integer","minimum":1}}}'''

    def "FR9: settings the schema allows are accepted, and an empty schema allows any settings"() {
        expect:
        SettingsSchema.judge(schema(json), settings) == new SettingsJudgement.Accepted()

        where:
        json | settings
        STRICT | [maxTurns: 3]
        STRICT | [:]
        '{}' | [anything: [nested: [1, 'two', null, true, 2.5]]]
    }

    def "FR9, UX1: a typo in a property name is a violation at that property, by the additionalProperties rule, naming it"() {
        when:
        def judgement = SettingsSchema.judge(schema(STRICT), [maxTurn: 3])

        then:
        def violation = only(judgement)
        violation.path() == 'maxTurn'
        violation.rule() == 'additionalProperties'
        violation.explanation().forLog() == "property 'maxTurn' is not defined in the schema and the schema does not allow additional properties"
    }

    def "UX1: a violation is located by property names and array indexes, the property a rule names last"() {
        when:
        def judgement = SettingsSchema.judge(schema(json), settings)

        then:
        only(judgement).path() == path
        only(judgement).rule() == rule

        where:
        json | settings || path | rule
        '{"properties":{"a":{"type":"array","items":{"type":"string"}}}}' | [a: ['x', 3]] || 'a[1]' | 'type'
        '{"properties":{"o":{"properties":{"n":{"minimum":1}}}}}' | [o: [n: 0]] || 'o.n' | 'minimum'
        '{"properties":{"o":{"additionalProperties":false}}}' | [o: [typo: 1]] || 'o.typo' | 'additionalProperties'
        '{"required":["model"]}' | [:] || 'model' | 'required'
        '{"type":"array"}' | [:] || '' | 'type'
    }

    def "UX1: every violation is reported, not only the first"() {
        when:
        def judgement = SettingsSchema.judge(schema(STRICT), [maxTurn: 3, maxTurns: 0])

        then:
        judgement instanceof SettingsJudgement.Rejected
        ((SettingsJudgement.Rejected) judgement).violations()*.path() as Set == ['maxTurn', 'maxTurns'] as Set
    }

    def "FR9: a schema of another shipped draft, and a local reference, are read as written"() {
        expect:
        only(SettingsSchema.judge(schema(json), [n: 0])).rule() == 'minimum'

        where:
        json << [
            '{"$schema":"http://json-schema.org/draft-07/schema#","properties":{"n":{"$ref":"#/definitions/pos"}},"definitions":{"pos":{"minimum":1}}}',
            '{"$schema":"https://json-schema.org/draft/2020-12/schema","properties":{"n":{"$ref":"#/$defs/pos"}},"$defs":{"pos":{"minimum":1}}}'
        ]
    }

    def "NFR-S2: a schema that would load another document, or cannot compile, is unusable and nothing is fetched"() {
        when:
        def judgement = SettingsSchema.judge(schema(json), [n: 'x'])

        then:
        judgement instanceof SettingsJudgement.Unusable
        ((SettingsJudgement.Unusable) judgement).reason().forLog().contains(reason)

        where:
        json || reason
        // A port nothing listens on: a fetch would fail with a connection error, not this refusal.
        '{"properties":{"n":{"$ref":"http://127.0.0.1:9/pos.json"}}}' || "Schema from 'http://127.0.0.1:9/pos.json' is not allowed to be loaded"
        '{"properties":{"n":{"$ref":"classpath:schemas/executor-describe.schema.json"}}}' || 'is not allowed to be loaded'
        '{"$schema":"https://schemas.example/meta"}' || "Schema from 'https://schemas.example/meta' is not allowed to be loaded"
        '{"properties":{"n":{"pattern":"["}}}' || 'Unclosed character class'
    }

    def "UX1: the validator's words are the same on every machine, whatever its default locale"() {
        given:
        def saved = Locale.getDefault()
        Locale.setDefault(Locale.GERMAN)

        when:
        def judgement = SettingsSchema.judge(schema(STRICT), [maxTurn: 3])

        then:
        only(judgement).explanation().forLog().contains('is not defined in the schema')

        cleanup:
        Locale.setDefault(saved)
    }

    private static SettingsViolation only(SettingsJudgement judgement) {
        assert judgement instanceof SettingsJudgement.Rejected
        def violations = ((SettingsJudgement.Rejected) judgement).violations()
        assert violations.size() == 1
        violations.first()
    }

    private static def schema(String json) {
        WireJson.readTree(json)
    }
}
