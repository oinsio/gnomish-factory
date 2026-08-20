package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.IsolationLevel
import com.github.oinsio.gnomish.sandbox.SandboxBindingProvider
import spock.lang.Specification

/**
 * ContainerBindingProvider: this backend module contributes the {@code container} binding and the
 * passport it claims (FR2, FR3 of open-adapter-binding-registry).
 *
 * The point of the change is visible here: the binding travels with the module that implements the
 * backend, so it is declared — and asserted — where the backend lives, not in a core enum. The
 * declaration is read without touching the Docker CLI or a daemon, which is what keeps binding
 * enumeration and planning-time reconciliation daemon-free.
 *
 * Implements FR1, FR2, FR3, FR10 of open-adapter-binding-registry.
 */
class ContainerBindingProviderSpec extends Specification {

    // FR3: the container binding's name and passport, asserted against literals rather than against
    // another call to the same factory method — a stubbed-out factory is caught, not masked
    def "the provider declares the container binding with the container passport"() {
        given: 'the provider as ServiceLoader would build it — public no-arg constructor'
        def provider = new ContainerBindingProvider()

        expect: 'the documented config name and the sandboxed passport, docker-inside excluded'
        provider.configName() == BindingNames.CONTAINER
        provider.passport() == new CapabilityPassport(IsolationLevel.CONTAINER, true, true, false)
    }

    // FR2: reading the descriptor starts nothing — no daemon, no CLI, no environment
    def "the descriptor is readable with no docker daemon and no live environment"() {
        expect: 'repeated reads answer identically from stateless instances'
        new ContainerBindingProvider().passport() == new ContainerBindingProvider().passport()

        and: 'the provider carries no field it could hold a live adapter in'
        ContainerBindingProvider.declaredFields.findAll {
            !it.synthetic
        }.isEmpty()
    }

    // FR1/FR3: the module's own META-INF/services entry is what makes the binding available —
    // guarded here so a lost or misspelled entry fails in this module rather than at an operator's
    // first run
    def "the module's service entry contributes the container binding to a discovery pass"() {
        when: 'a plain ServiceLoader pass over the module classpath is ratified'
        def registry = AdapterBindingRegistry.ratified(
                ServiceLoader.load(SandboxBindingProvider), BindingTrustTable.firstParty())

        then: 'the container binding is registered, carrying the ratified passport'
        registry.require(BindingNames.CONTAINER).passport() == CapabilityPassport.container()
        registry.bindings()[BindingNames.CONTAINER].configName() == BindingNames.CONTAINER
    }
}
