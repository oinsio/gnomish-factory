package com.github.oinsio.gnomish.adapter.sandbox

import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingResolver
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxBindingProvider
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link SandboxBindingDiscovery}: the binding registry is built by {@code ServiceLoader} over the
 * real classpath, and it is built deterministically (FR1, FR3, NFR-R1, M3, M4 of
 * open-adapter-binding-registry).
 *
 * The first feature is the packaging contract itself: the two {@code META-INF/services} entries the
 * distribution ships are discovered by a plain discovery pass, so a lost or misspelled entry file
 * fails here rather than at an operator's first run. The rest stage presence and absence on class
 * loaders of their own: a stand-in backend nobody's production source names becomes selectable, and
 * hiding the docker backend's registrations drops the container binding cleanly.
 *
 * Implements FR1, FR3, NFR-R1, NFR-S1 of open-adapter-binding-registry.
 */
class SandboxBindingDiscoverySpec extends Specification {

    @TempDir
    Path backendJarRoot

    // FR3: the shipped service entries are what makes host and container available — asserted on
    // the real classpath, so the entry files themselves are guarded
    def "the production service entries contribute the host and container bindings"() {
        when: 'the production discovery pass runs over the running build classpath'
        def registry = SandboxBindingDiscovery.discover()

        then: 'both shipped bindings are registered with their passports'
        registry.names().containsAll([
            BindingNames.HOST,
            BindingNames.CONTAINER
        ])
        registry.require(BindingNames.HOST).passport() == CapabilityPassport.hostNoIsolation()
        registry.require(BindingNames.CONTAINER).passport() == CapabilityPassport.container()
    }

    // M4/FR1: a backend the core names nowhere becomes selectable through the SPI alone — the
    // extension point, exercised with no edit to the discovery or registry mechanism
    def "a service entry the core names nowhere is discovered and becomes selectable"() {
        given: 'a backend "jar": a service entry naming a provider no production source references'
        def loader = stagedBackend()

        when: 'discovery runs with the stand-in registered in an injected trust table'
        def registry = SandboxBindingDiscovery.discover(loader, trustedWithStandIn())

        then: 'the stand-in is selectable beside the two bindings the build itself ships'
        registry.require(StubVmBindingProvider.CONFIG_NAME).passport() == StubVmBindingProvider.PASSPORT
        registry.names().containsAll([
            BindingNames.HOST,
            BindingNames.CONTAINER,
            StubVmBindingProvider.CONFIG_NAME
        ])

        and: 'an operator can then bind it by name with no further change'
        new BindingResolver(new BindingProperties(StubVmBindingProvider.CONFIG_NAME, [:]), registry)
        .resolve('build')
        .configName() == StubVmBindingProvider.CONFIG_NAME

        cleanup:
        loader.close()
    }

    // NFR-S1/FR7: the same staged backend without a trust-table entry is refused — discovery is
    // gated by the core's own table, not by what the classpath happens to carry
    def "a staged backend absent from the trust table is refused fail-fast"() {
        given: 'the same staged service entry, ratified against the production trust table'
        def loader = stagedBackend()

        when:
        SandboxBindingDiscovery.discover(loader, BindingTrustTable.firstParty())

        then: 'the untrusted binding aborts the registry build, naming it and the trusted ids'
        def failure = thrown(IllegalStateException)
        failure.message.contains("untrusted sandbox binding '" + StubVmBindingProvider.CONFIG_NAME + "'")
        failure.message.contains(StubVmBindingProvider.name)

        cleanup:
        loader.close()
    }

    // M3: removing the docker backend removes the container binding cleanly — no dangling reference
    def "hiding the docker backend's registrations drops the container binding"() {
        given: 'the classpath with the docker backend artifact taken away'
        def withoutDocker = SandboxDockerArtifact.hiddenFrom(getClass().classLoader)

        when:
        def registry = SandboxBindingDiscovery.discover(withoutDocker, BindingTrustTable.firstParty())

        then: 'the container binding is gone and the core-contributed host binding remains'
        registry.find(BindingNames.CONTAINER) == null
        registry.names() as List == [BindingNames.HOST]

        and: 'the same pass with the artifact present does find it — the removal is what did it'
        SandboxBindingDiscovery.discover().find(BindingNames.CONTAINER) != null
    }

    // M3/FR4/D4: and the container default is then unsatisfiable, refused with the options named
    def "with the docker backend hidden the container default fails fast naming the options"() {
        given: 'a stripped distribution and an operator who configured no default'
        def registry = SandboxBindingDiscovery.discover(
                SandboxDockerArtifact.hiddenFrom(getClass().classLoader), BindingTrustTable.firstParty())

        when:
        new BindingResolver(new BindingProperties(null, [:]), registry)

        then: 'startup fails naming the discovered options and both ways out — never a host fallback'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains(BindingNames.CONTAINER)
        failure.message.contains('[host]')
        failure.message.contains('factory.bindings.default=host')
    }

    /** A loader carrying a service entry for the stand-in backend, as its jar would. */
    private URLClassLoader stagedBackend() {
        Path services = backendJarRoot.resolve('META-INF/services')
        Files.createDirectories(services)
        Files.writeString(services.resolve(SandboxBindingProvider.name), StubVmBindingProvider.name + '\n')
        new URLClassLoader([
            backendJarRoot.toUri().toURL()
        ] as URL[], getClass().classLoader)
    }

    /** The production trust table plus the stand-in's own registration — the reviewed one-liner. */
    private static Map<String, CapabilityPassport> trustedWithStandIn() {
        BindingTrustTable.firstParty() + [(StubVmBindingProvider.CONFIG_NAME): StubVmBindingProvider.PASSPORT]
    }
}
