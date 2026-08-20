package com.github.oinsio.gnomish.sandbox

import spock.lang.Specification

/**
 * SandboxBindingProvider: the first-party contribution point a backend module
 * ships one of per binding (FR1, FR2, FR3, D5 of open-adapter-binding-registry).
 * A provider is a descriptor — it names its binding and declares a passport — and
 * both answers come from a no-arg-constructed instance with no live environment,
 * no backend SDK and no daemon behind them, which is what keeps binding
 * enumeration and planning-time reconciliation adapter-instance-free.
 *
 * FR2: a provider describes its binding without instantiating a backend adapter.
 * FR3: host is contributed from :sandbox:core with the no-isolation passport.
 */
class SandboxBindingProviderSpec extends Specification {

    // FR2/D5: the SPI is descriptor-only — a no-arg instance answers both questions
    def "a no-arg-constructed provider exposes its config name and passport"() {
        given: 'a provider built the way ServiceLoader builds one'
        def provider = SandboxBindingProvider.class
                .getClassLoader()
                .loadClass('com.github.oinsio.gnomish.sandbox.HostBindingProvider')
                .getDeclaredConstructor()
                .newInstance() as SandboxBindingProvider

        expect: 'both descriptor answers are available with nothing else constructed'
        provider.configName() == 'host'
        provider.passport() == new CapabilityPassport(IsolationLevel.NONE, false, false, true)
    }

    // FR2: the SPI carries no environment factory in this change (D5) — the deferral is asserted,
    // so a later addition is a deliberate edit rather than an accident
    def "the SPI declares exactly the two descriptor methods and no environment factory"() {
        expect: 'configName and passport are the whole contract, both returning descriptor values'
        SandboxBindingProvider.declaredMethods*.name.sort() == ['configName', 'passport']
        SandboxBindingProvider.getMethod('configName').returnType == String
        SandboxBindingProvider.getMethod('passport').returnType == CapabilityPassport
    }

    // FR3: the host binding is contributed from the port module itself, with its honest passport
    def "the host provider declares the host binding with the no-isolation passport"() {
        given: 'the core-contributed host provider'
        def provider = new HostBindingProvider()

        expect: 'it names the privileged host binding and declares no isolation'
        provider.configName() == BindingNames.HOST
        provider.passport() == new CapabilityPassport(IsolationLevel.NONE, false, false, true)
    }

    // FR2/D5: the descriptor holds no live adapter — it has no state at all to hold one in,
    // which is what keeps binding enumeration and reconciliation daemon-free
    def "the host provider carries no state, so it can hold no live adapter"() {
        expect: 'the provider declares no field of its own'
        HostBindingProvider.declaredFields.findAll { !it.synthetic }.isEmpty()

        and: 'repeated reads answer identically without anything being constructed between them'
        new HostBindingProvider().passport() == new HostBindingProvider().passport()
    }
}
