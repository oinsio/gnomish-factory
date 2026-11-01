package com.github.oinsio.gnomish.executorcontract

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import spock.lang.Specification

/**
 * {@link ExecutorContractJson#settingsTree}, the one conversion of the law's settings into the tree a
 * request sends and the settings schema judges (FR9 of define-executor-contract, task 7.1): the same
 * values give the same tree on both paths.
 */
class SettingsTreeSpec extends Specification {

    // FR9: plain law values become their JSON tree, nested maps and lists included
    def "FR9: the law's settings become the JSON tree a request carries"() {
        when:
        def tree = ExecutorContractJson.settingsTree([maxTurns: 3, flags: ['a', 'b'], nested: [on: true], none: null])

        then:
        tree.get('maxTurns').intValue() == 3
        tree.get('flags').size() == 2
        tree.get('nested').get('on').booleanValue()
        tree.get('none').isNull()
    }

    // FR9: the schema judges exactly the tree the conversion gives
    def "FR9: a settings map the schema refuses as a tree is refused by the judge"() {
        given:
        def schema = JsonNodeFactory.instance.objectNode().put('type', 'object').put('additionalProperties', false)

        expect:
        SettingsSchema.judge(schema, [:]) instanceof SettingsJudgement.Accepted
        SettingsSchema.judge(schema, [x: 1]) instanceof SettingsJudgement.Rejected
    }
}
