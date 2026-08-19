package com.github.oinsio.gnomish.sandbox

import static com.github.oinsio.gnomish.sandbox.BindingFixtures.*

import spock.lang.Specification

/**
 * AdapterBindingRegistry, the trust half: discovery is gated by the core-owned
 * trust table, so a provider's self-declared passport is a cross-check and never
 * the authority (FR7, FR10, NFR-S1, design D2 of open-adapter-binding-registry).
 * On a flat classpath ServiceLoader would otherwise load any jar's provider and
 * let it assert a lying passport — and the passport is exactly what
 * reconciliation trusts.
 *
 * Driven by an injected trust table, which is also what makes the extension-point
 * acceptance test honest: a stub first-party backend is ratified by a table of
 * the spec's own, with no edit to the mechanism.
 *
 * FR7: only bindings the core vouches for are registered.
 * FR10: a passport differing from the expected one is rejected fail-fast.
 */
class BindingRatificationSpec extends Specification {

    // FR7/FR10: the happy path — a table entry plus a matching declaration registers the binding
    def "a provider with a table entry and a matching passport is registered"() {
        given: 'a trust table naming one binding and a provider declaring exactly it'
        def table = [vm: CapabilityPassport.container()]

        when: 'the provider is ratified against it'
        def registry = AdapterBindingRegistry.ratified([
            provider('vm', CapabilityPassport.container())
        ], table)

        then: 'the binding is registered'
        registry.require('vm').configName() == 'vm'
        registry.require('vm').passport() == CapabilityPassport.container()
    }

    // FR7/NFR-S1: an id the core does not vouch for never reaches the registry
    def "an id absent from the trust table is rejected fail-fast and not registered"() {
        when: 'a provider declares a binding the trust table does not name'
        AdapterBindingRegistry.ratified(
                [
                    provider('rogue', CapabilityPassport.hostNoIsolation())
                ], BindingTrustTable.firstParty())

        then: 'the build refuses, naming the untrusted id, the trusted ids, and both fixes'
        def failure = thrown(IllegalStateException)
        failure.message.contains("untrusted sandbox binding 'rogue'")
        failure.message.contains('host')
        failure.message.contains('container')
        failure.message.contains('trust table')
    }

    // FR10/NFR-S1: the declaration is a tripwire — a passport that differs is a refusal
    def "a declared passport differing from the expected one is rejected fail-fast"() {
        when: 'a provider claims the container binding but declares the weaker host passport'
        AdapterBindingRegistry.ratified(
                [
                    provider(BindingNames.CONTAINER, CapabilityPassport.hostNoIsolation())
                ],
                BindingTrustTable.firstParty())

        then: 'the build refuses, naming the binding, both passports, and the fix'
        def failure = thrown(IllegalStateException)
        failure.message.contains("sandbox binding 'container'")
        failure.message.contains('NONE')
        failure.message.contains('trust table')
    }

    // FR10: the table is the authority — a registered binding carries the table's passport
    def "a registered binding carries the trust table's passport, not the provider's copy"() {
        given: 'a provider whose passport is an equal but distinct instance'
        def declared = new CapabilityPassport(IsolationLevel.CONTAINER, true, true, false)
        def registry = AdapterBindingRegistry.ratified(
                [
                    provider(BindingNames.CONTAINER, declared)
                ], BindingTrustTable.firstParty())

        expect: 'the registered passport equals the trusted one'
        registry.require('container').passport() == BindingTrustTable.firstParty()[BindingNames.CONTAINER]
    }

    // NFR-S1/D2: the production table vouches for exactly the two first-party bindings this
    // distribution ships, each with the passport its provider must declare
    def "the production trust table registers host and container with their expected passports"() {
        expect: 'the two ids and their passports, asserted against literals rather than the factories'
        BindingTrustTable.firstParty().keySet() as List == ['host', 'container']
        BindingTrustTable.firstParty()[BindingNames.HOST] ==
                new CapabilityPassport(IsolationLevel.NONE, false, false, true)
        BindingTrustTable.firstParty()[BindingNames.CONTAINER] ==
                new CapabilityPassport(IsolationLevel.CONTAINER, true, true, false)
    }
}
