package com.github.oinsio.gnomish.adapter.environment

import com.github.oinsio.gnomish.BindingProperties
import spock.lang.Specification

/**
 * BindingResolver: resolves the adapter binding a stage runs under from operator
 * config (design D8, D13; FR14, G2 of add-sandbox-core). The default is the
 * container adapter whenever the operator configures none — no silent host
 * fallback — and per-stage overrides win over the default. Unknown binding names
 * fail eagerly at construction.
 *
 * FR14/G2: bindings resolve container-by-default with named-override precedence,
 * and unknown names are rejected up front.
 */
class BindingResolverSpec extends Specification {

    private static BindingResolver resolver(String defaultBinding, Map<String, String> stages) {
        new BindingResolver(new BindingProperties(defaultBinding, stages))
    }

    // D13/G2: with no default configured, every stage binds the container adapter — never host
    def "the default binding is container when the operator configures none"() {
        given: 'a resolver with no configured default and no overrides'
        def resolver = resolver(null, [:])

        expect: 'any stage resolves to the container adapter, not a silent host fallback'
        resolver.resolve('build') == AdapterBinding.CONTAINER
        resolver.resolve('anything') == AdapterBinding.CONTAINER
    }

    // FR14: an explicit default binding is applied to unoverridden stages
    def "an explicit default binding is applied to unoverridden stages"() {
        given: 'a resolver with host as the explicit default'
        def resolver = resolver('host', [:])

        expect: 'a stage with no override resolves to the configured default'
        resolver.resolve('build') == AdapterBinding.HOST
    }

    // FR14: a per-stage override wins over the default
    def "a per-stage override wins over the default binding"() {
        given: 'a container default with a host override for one stage'
        def resolver = resolver('container', [debug: 'host'])

        expect: 'the overridden stage takes the override and others take the default'
        resolver.resolve('debug') == AdapterBinding.HOST
        resolver.resolve('build') == AdapterBinding.CONTAINER
    }

    // FR14/UX2: an unknown default binding name fails at construction
    def "an unknown default binding name is rejected at construction"() {
        when: 'a resolver is built with an unknown default binding'
        resolver('vm', [:])

        then: 'construction fails naming the unknown value'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("'vm'")
    }

    // FR14/UX2: an unknown per-stage binding name fails at construction
    def "an unknown per-stage binding name is rejected at construction"() {
        when: 'a resolver is built with an unknown per-stage binding'
        resolver(null, [build: 'vm'])

        then: 'construction fails naming the unknown value'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("'vm'")
    }
}
