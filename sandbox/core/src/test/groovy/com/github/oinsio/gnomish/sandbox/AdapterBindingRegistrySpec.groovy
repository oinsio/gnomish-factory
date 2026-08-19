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

    // FR5/UX1: an unknown name is refused with the discovered options and the fix named
    def "an unknown binding name is refused naming the discovered options and the fix"() {
        when: 'a name no provider contributes is required'
        hostAndContainer().require('vm')

        then: 'the refusal names the value, both discovered options, and how to fix it'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("'vm'")
        failure.message.contains('host')
        failure.message.contains('container')
        failure.message.contains('factory.bindings')
        failure.message.contains('classpath')
    }

    // FR8/NFR-R1: two providers claiming one name is a refusal, never an arbitrary pick
    def "two providers claiming one config name fail fast naming both"() {
        when: 'two providers both declare the container binding'
        registryOf([
            provider(BindingNames.CONTAINER, CapabilityPassport.container()),
            provider(BindingNames.CONTAINER, CapabilityPassport.container())
        ])

        then: 'the build refuses, naming the conflicting binding and both declaring classes'
        def failure = thrown(IllegalStateException)
        failure.message.contains("duplicate sandbox binding 'container'")
        failure.message.contains('classpath')
    }

    // NFR-R1: a provider that names nothing cannot be configured, so it is refused at build time
    def "a provider declaring a blank config name is refused naming the provider class"() {
        when: 'a provider declares a blank name'
        registryOf([
            provider(blank, CapabilityPassport.container())
        ])

        then: 'the build refuses naming the offending provider class'
        def failure = thrown(IllegalStateException)
        failure.message.contains('configName()')

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
