package com.github.oinsio.gnomish.executorcontract

import spock.lang.Specification

/**
 * FR2, FR9 of define-executor-contract: the two names the contract's callers key by — an executor
 * name and a pending key — refuse a value outside their shape and print as their value.
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

    def "FR2: a pending key prints as its value"() {
        expect:
        new PendingKey('build-8812').toString() == 'build-8812'
    }
}
