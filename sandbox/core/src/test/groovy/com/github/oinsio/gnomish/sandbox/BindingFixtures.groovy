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

    /**
     * A second provider class declaring the same shape, so a refusal that must name <em>both</em>
     * declaring classes is falsifiable — two instances of one stub class would name one class twice.
     */
    static SandboxBindingProvider rivalProvider(String name, CapabilityPassport passport) {
        new RivalBindingProvider(name, passport)
    }

    /** {@code providers} ratified against the production first-party trust table. */
    static AdapterBindingRegistry registryOf(List<SandboxBindingProvider> providers) {
        AdapterBindingRegistry.ratified(providers, BindingTrustTable.firstParty())
    }

    /** The production pair — host from core, a stand-in for the docker module's container. */
    static AdapterBindingRegistry hostAndContainer() {
        registryOf([
            new HostBindingProvider(),
            provider(BindingNames.CONTAINER, CapabilityPassport.container())
        ])
    }

    /** A registry with the container backend module absent, as a stripped distribution has (M3). */
    static AdapterBindingRegistry hostOnly() {
        registryOf([new HostBindingProvider()])
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

    /** A rival module's provider — same declaration, different class, as a name clash really is. */
    private static class RivalBindingProvider extends StubBindingProvider {

        RivalBindingProvider(String name, CapabilityPassport passport) {
            super(name, passport)
        }
    }
}
