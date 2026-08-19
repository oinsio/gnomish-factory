package com.github.oinsio.gnomish.sandbox

import static com.github.oinsio.gnomish.sandbox.BindingFixtures.*

import spock.lang.Specification

/**
 * AdapterBindingRegistry, the index half: bindings are assembled from discovered
 * providers rather than enumerated in core, and every ambiguity is a refusal
 * naming the fix (FR1, FR5, FR8, NFR-R1 of open-adapter-binding-registry).
 * Driven by an injected provider list — no jars, no ServiceLoader — so the rules
 * are exercised as the pure logic they are.
 *
 * FR1: a provider's binding joins the registry with no core enum edit.
 * FR8: two providers claiming one config name fail fast naming the conflict.
 * FR5: an unknown requested name fails fast listing the discovered options.
 */
class AdapterBindingRegistrySpec extends Specification {

    // FR1/FR3: each discovered provider contributes its binding, carrying the ratified passport
    def "discovered providers become bindings keyed by their config name"() {
        when: 'the host and container providers are indexed'
        def registry = hostAndContainer()

        then: 'both bindings are registered with their passports'
        registry.names() as List == ['host', 'container']
        registry.require('host') == hostBinding()
        registry.require('container') == containerBinding()

        and: 'the same pair is what the startup report enumerates, keyed by config name'
        registry.bindings() == [host: hostBinding(), container: containerBinding()]
    }

    // FR1: the registry is the whole enumeration — nothing is registered that nobody contributed
    def "a binding no provider contributes is simply absent"() {
        expect: 'the container binding is missing when its module is not on the classpath'
        hostOnly().find('container') == null
        hostOnly().names() as List == ['host']
    }

    // FR5/UX1: an unknown name is refused with the discovered options and the fix named.
    // The table is the one the superseded AdapterBindingSpec carried: config names are matched
    // exactly, so a differently-cased name is unknown rather than helpfully coerced, and an
    // empty name is a name nobody contributed rather than a silent default.
    def "an unknown binding name #name is refused naming the discovered options and the fix"() {
        when: 'a name no provider contributes is required'
        hostAndContainer().require(name)

        then: 'the refusal names the value, both discovered options, and how to fix it'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("'" + name + "'")
        failure.message.contains('host')
        failure.message.contains('container')
        failure.message.contains('factory.bindings')
        failure.message.contains('classpath')

        where:
        name << ['vm', 'Host', 'CONTAINER', '']
    }

    // FR1/FR5: the exact-match rule has a positive half too — the documented spellings resolve
    def "the documented config names resolve exactly as spelled"() {
        expect: 'each shipped name resolves to its binding'
        hostAndContainer().require('host') == hostBinding()
        hostAndContainer().require('container') == containerBinding()
    }

    // FR8/NFR-R1: two providers claiming one name is a refusal, never an arbitrary pick
    def "two providers claiming one config name fail fast naming both"() {
        given: 'two providers of different classes, as two modules colliding really are'
        def first = provider(BindingNames.CONTAINER, CapabilityPassport.container())
        def second = rivalProvider(BindingNames.CONTAINER, CapabilityPassport.container())

        when: 'both declare the container binding'
        registryOf([first, second])

        then: 'the build refuses, naming the conflicting binding and both declaring classes'
        def failure = thrown(IllegalStateException)
        failure.message.contains("duplicate sandbox binding 'container'")
        failure.message.contains(first.class.name)
        failure.message.contains(second.class.name)

        and: 'and the fix — one of the two modules has to go'
        failure.message.contains('remove one of the two modules from the classpath')
    }

    // NFR-R1: a provider that names nothing cannot be configured, so it is refused at build time
    def "a provider declaring a blank config name is refused naming the provider class"() {
        given: 'a provider that names itself nothing'
        def offender = provider(blank, CapabilityPassport.container())

        when: 'it is indexed'
        registryOf([offender])

        then: 'the build refuses naming the offending provider class and what it failed to do'
        def failure = thrown(IllegalStateException)
        failure.message.contains(offender.class.name)
        failure.message.contains('configName()')

        and: 'and the fix — a binding has to name itself to be configurable'
        failure.message.contains('must name itself to be configurable')

        where:
        blank << ['', '   ']
    }

    // UX1/NFR-O1: the options an operator reads follow discovery order, not a per-JVM hash order
    def "discovery order is preserved whichever order the providers arrive in"() {
        given: 'the same two providers, contributed in the reverse order'
        def reversed = registryOf([
            provider(BindingNames.CONTAINER, CapabilityPassport.container()),
            new HostBindingProvider()
        ])

        expect: 'each registry enumerates its own encounter order, both ways round'
        hostAndContainer().names() as List == ['host', 'container']
        reversed.names() as List == ['container', 'host']
        reversed.bindings().keySet() as List == ['container', 'host']
    }

    // NFR-O1: the registry remembers which provider declared each binding — the origin half of the
    // startup report, which for a trust boundary with no runtime enforcement is what an operator
    // reads instead of an enforcement guarantee
    def "each binding records the provider class that declared it, in discovery order"() {
        given: 'the two providers this distribution ships, stood in for'
        def container = provider(BindingNames.CONTAINER, CapabilityPassport.container())

        when: 'they are indexed'
        def registry = registryOf([
            new HostBindingProvider(),
            container
        ])

        then: 'each config name maps to its own declaring class, in encounter order'
        registry.providerTypes() == [
            (BindingNames.HOST): HostBindingProvider,
            (BindingNames.CONTAINER): container.class
        ]
        registry.providerTypes().keySet() as List == ['host', 'container']
    }

    // FR1: an empty classpath contribution is an empty registry, not a hidden default
    def "no providers means no bindings"() {
        expect: 'nothing is invented when nothing is discovered'
        registryOf([]).names().isEmpty()
        registryOf([]).bindings().isEmpty()
    }

    // FR1: the registry is frozen once built — the binding set is process-static
    def "the exposed binding map is immutable"() {
        when: 'a caller tries to add a binding after the build'
        hostAndContainer().bindings().put('vm', containerBinding())

        then: 'the map rejects the mutation'
        thrown(UnsupportedOperationException)
    }
}
