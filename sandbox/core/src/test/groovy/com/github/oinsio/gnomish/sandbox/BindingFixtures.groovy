package com.github.oinsio.gnomish.sandbox

/**
 * Shared binding fixtures for the registry-backed sandbox specs (design D2, D3 of
 * open-adapter-binding-registry). Bindings are no longer enum constants a spec can
 * name: they are values a discovered provider contributes and the trust table
 * ratifies, so every spec that needs one builds it the way production does —
 * from a provider, through {@link AdapterBindingRegistry}.
 *
 * The stub provider is what keeps the registry's injectability honest: the
 * production classpath contributes {@code host} from core and {@code container}
 * from the docker module, and a spec in the port module stages both without
 * either jar.
 */
final class BindingFixtures {

    private BindingFixtures() {}

    /** A provider declaring {@code name} with {@code passport}, as a backend module would. */
    static SandboxBindingProvider provider(String name, CapabilityPassport passport) {
        new StubBindingProvider(name, passport)
    }

    /** The production pair — host from core, a stand-in for the docker module's container. */
    static AdapterBindingRegistry hostAndContainer() {
        AdapterBindingRegistry.ratified(
                [
                    new HostBindingProvider(),
                    provider(BindingNames.CONTAINER, CapabilityPassport.container())
                ],
                BindingTrustTable.firstParty())
    }

    /** A registry with the container backend module absent, as a stripped distribution has (M3). */
    static AdapterBindingRegistry hostOnly() {
        AdapterBindingRegistry.ratified([new HostBindingProvider()], BindingTrustTable.firstParty())
    }

    static AdapterBinding containerBinding() {
        new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container())
    }

    static AdapterBinding hostBinding() {
        new AdapterBinding(BindingNames.HOST, CapabilityPassport.hostNoIsolation())
    }

    /** A backend module's provider, stood in for without the module. */
    private static class StubBindingProvider implements SandboxBindingProvider {

        private final String name
        private final CapabilityPassport passport

        StubBindingProvider(String name, CapabilityPassport passport) {
            this.name = name
            this.passport = passport
        }

        @Override
        String configName() {
            name
        }

        @Override
        CapabilityPassport passport() {
            passport
        }
    }
}
