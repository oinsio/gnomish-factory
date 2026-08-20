package com.github.oinsio.gnomish.sandbox

import static com.github.oinsio.gnomish.sandbox.BindingFixtures.*

import spock.lang.Specification

/**
 * BindingResolver: resolves the adapter binding a stage runs under from operator
 * config against the discovered registry (design D8, D13; FR14, G2 of
 * add-sandbox-core; FR4, FR5, D4 of open-adapter-binding-registry). The default is
 * the container binding whenever the operator configures none — no silent host
 * fallback — and per-stage overrides win over the default. Names that no provider
 * contributes fail eagerly at construction, and so does an unset default whose
 * container backend module is absent.
 *
 * FR14/G2: bindings resolve container-by-default with named-override precedence.
 * FR5: unknown names are rejected up front, listing the discovered options.
 * FR4: the container default is resolved eagerly and never weakened to host.
 *
 * UX2/M2 of open-adapter-binding-registry: an existing host / container / unset
 * configuration runs with zero migration burden — the four behavioural features
 * below are the pre-registry ones with their assertions untouched; only the
 * construction sites that named the removed enum constants were migrated.
 */
class BindingResolverSpec extends Specification {

    private static BindingResolver resolver(
            String defaultBinding, Map<String, String> stages, AdapterBindingRegistry registry = hostAndContainer()) {
        new BindingResolver(new BindingProperties(defaultBinding, stages), registry)
    }

    // D13/G2/FR4: with no default configured, every stage binds the container adapter — never host
    def "the default binding is container when the operator configures none"() {
        given: 'a resolver with no configured default and no overrides'
        def resolver = resolver(null, [:])

        expect: 'any stage resolves to the container adapter, not a silent host fallback'
        resolver.resolve('build') == containerBinding()
        resolver.resolve('anything') == containerBinding()
    }

    // FR14: an explicit default binding is applied to unoverridden stages
    def "an explicit default binding is applied to unoverridden stages"() {
        given: 'a resolver with host as the explicit default'
        def resolver = resolver('host', [:])

        expect: 'a stage with no override resolves to the configured default'
        resolver.resolve('build') == hostBinding()
    }

    // FR14: a per-stage override wins over the default
    def "a per-stage override wins over the default binding"() {
        given: 'a container default with a host override for one stage'
        def resolver = resolver('container', [debug: 'host'])

        expect: 'the overridden stage takes the override and others take the default'
        resolver.resolve('debug') == hostBinding()
        resolver.resolve('build') == containerBinding()
    }

    // FR5/UX1: an unknown default binding name fails at construction, listing what was discovered
    def "an unknown default binding name is rejected at construction naming the discovered options"() {
        when: 'a resolver is built with an unknown default binding'
        resolver('vm', [:])

        then: 'construction fails naming the unknown value and both discovered bindings'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("'vm'")
        failure.message.contains('host')
        failure.message.contains('container')
    }

    // FR5/UX1: an unknown per-stage binding name fails at construction
    def "an unknown per-stage binding name is rejected at construction"() {
        when: 'a resolver is built with an unknown per-stage binding'
        resolver(null, [build: 'vm'])

        then: 'construction fails naming the unknown value'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("'vm'")
    }

    // FR4/M3/D4: the unset default needs the container binding to exist — never fall through to host
    def "an unset default with the container backend absent fails naming the options and both ways out"() {
        when: 'a resolver is built against a registry the container module never reached'
        resolver(null, [:], hostOnly())

        then: 'construction fails rather than silently binding host'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('container')
        failure.message.contains('[host]')
        failure.message.contains('factory.bindings.default=host')
    }

    // FR4/D4: the default is resolved eagerly — explicit host stage bindings do not mask its absence
    def "the absent container default fails even when every stage explicitly binds host"() {
        when: 'every stage binds host explicitly but no default is configured'
        resolver(null, [plan: 'host', build: 'host'], hostOnly())

        then: 'the unsatisfiable declared default still fails at construction'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('container')
    }

    // FR4: an explicitly configured host default needs no container binding at all
    def "an explicit host default resolves in a distribution without the container backend"() {
        given: 'a stripped distribution with host explicitly bound as the default'
        def resolver = resolver('host', [:], hostOnly())

        expect: 'resolution succeeds — only the unset default requires the container binding'
        resolver.resolve('build') == hostBinding()
    }
}
