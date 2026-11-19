package com.github.oinsio.gnomish.executorcontract

import spock.lang.Specification

/**
 * FR9 of define-executor-contract: the executor name the contract's callers key by refuses a value
 * outside its shape. The pending key's own spec moved with its type to the `:pendingkey` leaf
 * (`PendingKeySpec`, design D20 of migrate-verify-to-executor-contract).
 */
class ValueTypesSpec extends Specification {

    def "FR9: an executor name is lower case, starts with a letter and is at most 64 characters"() {
        expect:
        new ExecutorName(name).toString() == name

        where:
        name << [
            'acme',
            'claude-code',
            'a_1',
            'a' * 64
        ]
    }

    def "FR9: the law loader's name check agrees with the type for '#name'"() {
        expect:
        ExecutorName.isName(name) == accepted
        ExecutorName.grammar() == '[a-z][a-z0-9_-]{0,63}'

        where:
        name || accepted
        'acme' || true
        'Acme' || false
        'a' * 65 || false
    }

    def "FR9: a credential variable name is upper case, digits and underscore, not led by a digit"() {
        expect:
        VariableNames.isVariableName(name) == accepted
        VariableNames.grammar() == '[A-Z_][A-Z0-9_]*'

        where:
        name || accepted
        'ACME_API_KEY' || true
        '_X1' || true
        '1X' || false
        'acme' || false
        '' || false
    }

    def "FR9: '#name' is not an executor name"() {
        when:
        new ExecutorName(name)

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message.endsWith(": ${name}")

        where:
        name << [
            '',
            'Acme',
            '1acme',
            'acme!',
            'a' * 65
        ]
    }
}
