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
        given: 'a provider claiming an id the core vouches for nothing about'
        def rogue = provider('rogue', CapabilityPassport.hostNoIsolation())

        when: 'it is ratified against the production table'
        AdapterBindingRegistry.ratified([rogue], BindingTrustTable.firstParty())

        then: 'the build refuses, naming the untrusted id, its declaring class, and the trusted ids'
        def failure = thrown(IllegalStateException)
        failure.message.contains("untrusted sandbox binding 'rogue'")
        failure.message.contains(rogue.class.name)
        failure.message.contains('host')
        failure.message.contains('container')

        and: 'and both ways out — drop the module, or vouch for the binding in core'
        failure.message.contains('remove the module from the classpath')
        failure.message.contains('register the binding and its expected passport in the core trust table')
    }

    // FR10/NFR-S1: the declaration is a tripwire — a passport that differs is a refusal
    def "a declared passport differing from the expected one is rejected fail-fast"() {
        given: 'a provider claiming the container binding but declaring the weaker host passport'
        def liar = provider(BindingNames.CONTAINER, CapabilityPassport.hostNoIsolation())

        when: 'it is ratified against the production table'
        AdapterBindingRegistry.ratified([liar], BindingTrustTable.firstParty())

        then: 'the build refuses, naming the binding, its declaring class, and both passports'
        def failure = thrown(IllegalStateException)
        failure.message.contains("sandbox binding 'container'")
        failure.message.contains(liar.class.name)
        failure.message.contains(CapabilityPassport.hostNoIsolation().toString())
        failure.message.contains(BindingTrustTable.firstParty()[BindingNames.CONTAINER].toString())

        and: 'and the fix — the classpath carries a build core does not vouch for'
        failure.message.contains('restore the trusted module')
        failure.message.contains('update the core trust table if the change is intended')
    }

    // FR10: the table is the authority — a registered binding carries the table's passport
    def "a registered binding carries the trust table's passport, not the provider's copy"() {
        given: 'a trust table instance of the spec\'s own, and an equal but distinct declaration'
        def trusted = new CapabilityPassport(IsolationLevel.CONTAINER, true, true, false)
        def declared = new CapabilityPassport(IsolationLevel.CONTAINER, true, true, false)

        when: 'the provider is ratified against that table'
        def registry = AdapterBindingRegistry.ratified(
                [
                    provider(BindingNames.CONTAINER, declared)
                ], [(BindingNames.CONTAINER): trusted])

        then: 'the stored passport is the table\'s very instance — value equality cannot tell the two apart'
        registry.require('container').passport().is(trusted)
        !registry.require('container').passport().is(declared)
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
