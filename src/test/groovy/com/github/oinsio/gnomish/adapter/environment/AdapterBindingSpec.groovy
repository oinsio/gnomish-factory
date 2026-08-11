package com.github.oinsio.gnomish.adapter.environment

import spock.lang.Specification

/**
 * AdapterBinding: the operator's host/container adapter choice and its fixed
 * capability passport (design D8, D13; FR14 of add-sandbox-core). Each binding
 * carries the passport reconciliation checks needs against, and parsing a
 * configured name fails fast on an unknown value with the valid options named.
 *
 * FR14: bindings are typed, each with a passport; unknown names are rejected.
 */
class AdapterBindingSpec extends Specification {

    // FR14: each binding is spelled with its documented lower-case config name
    def "each binding exposes its config name"() {
        expect: 'the config names match the wire grammar'
        AdapterBinding.HOST.configName() == 'host'
        AdapterBinding.CONTAINER.configName() == 'container'
    }

    // FR2/FR3: each binding carries the fixed passport reconciliation checks against.
    // Asserted against explicit passport literals — never against another call to the
    // same factory method — so a stubbed-out (null-returning) factory is caught rather
    // than masked by both sides collapsing to the same value.
    def "each binding carries its fixed capability passport"() {
        expect: 'host declares no isolation'
        AdapterBinding.HOST.passport() == new CapabilityPassport(IsolationLevel.NONE, false, false, true)

        and: 'container declares the sandboxed passport with no docker-inside support'
        AdapterBinding.CONTAINER.passport() == new CapabilityPassport(IsolationLevel.CONTAINER, true, true, false)
    }

    // FR14: a configured name resolves to its binding
    def "parse resolves a configured name to its binding"() {
        expect: 'each documented name resolves to its constant'
        AdapterBinding.parse('host') == AdapterBinding.HOST
        AdapterBinding.parse('container') == AdapterBinding.CONTAINER
    }

    // FR14/UX2: an unknown name fails fast naming the value and the valid options
    def "parse of unknown name #name is rejected naming the value and valid options"() {
        when: 'an unknown binding name is parsed'
        AdapterBinding.parse(name)

        then: 'construction fails and the message names the value and both valid bindings'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("'$name'")
        failure.message.contains('host')
        failure.message.contains('container')

        where:
        name << ['vm', 'Host', 'CONTAINER', '']
    }
}
