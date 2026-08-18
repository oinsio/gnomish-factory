package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.app.CheckClientFactory
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link CheckClientDiscovery}: the check port's registry is built by {@code ServiceLoader}, keyed
 * by each provider's own discriminator — bringing the check port to tracker parity (FR5, design D1,
 * D3 of add-plugin-architecture).
 *
 * <p>The first two features are the parity claim itself: a factory declared in a {@code
 * META-INF/services} entry the build ships nowhere becomes selectable, and the bundled github
 * provider sits in that same registry as one entry among others rather than as a wired-in special
 * case — no core code names it to resolve it. The remaining features are the fail-fast rules
 * (NFR-R1): a blank discriminator and a collision both abort the registry build at startup, naming
 * the offenders, rather than surfacing at first poll.
 *
 * <p>Implements FR2, FR5, NFR-R1 of add-plugin-architecture.
 */
class CheckClientDiscoverySpec extends Specification {

    @TempDir
    Path pluginJarRoot

    // FR5, D3: "a check client is resolved by its provider" — a service entry no core source
    //     mentions is discovered and keyed, exactly as a third-party jar's would be.
    def "a service entry the core names nowhere is discovered and becomes selectable"() {
        given: 'a plugin "jar": a service entry naming a factory no core source references'
        Path services = pluginJarRoot.resolve('META-INF/services')
        Files.createDirectories(services)
        Files.writeString(
                services.resolve(CheckClientFactory.name),
                PluginStandInCheckClientFactory.name + '\n')
        def loader = new URLClassLoader(
                [pluginJarRoot.toUri().toURL()] as URL[], getClass().classLoader)

        when:
        def registry = CheckClientDiscovery.discover(loader)

        then: 'the plugin is selectable beside the provider the build itself ships'
        registry[PluginStandInCheckClientFactory.PROVIDER] instanceof PluginStandInCheckClientFactory
        registry.keySet().containsAll([
            'github',
            PluginStandInCheckClientFactory.PROVIDER
        ])

        cleanup:
        loader.close()
    }

    // FR5: github is one entry of the discovered registry, reached through its own declared
    //     discriminator — the "and no core code names github directly to make that resolution"
    //     half of the check-provider-model scenario.
    def "the bundled github provider is discovered through the same path, with no shortcut"() {
        when:
        def registry = CheckClientDiscovery.discover()

        then:
        registry['github'] != null
        registry['github'].provider() == 'github'
    }

    // FR2, design D2: ServiceLoader instantiates through the public no-arg constructor, so a
    //     discovered factory holds no injected SecretsProvider — it receives one per create(...).
    def "discovery builds every provider through its public no-arg constructor"() {
        given:
        def registry = CheckClientDiscovery.discover()

        expect:
        registry.every { provider, factory ->
            factory.class.getConstructor() != null
        }
    }

    // NFR-R1: a provider with no usable discriminator cannot be keyed, so the build stops with an
    //     error naming it rather than filing it under a blank key nothing can select.
    def "a #description discriminator fails the registry build, naming the provider"() {
        when:
        CheckClientDiscovery.index([factoryOfProvider(declared)])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('check')
        ex.message.contains('declares no provider() discriminator')
        ex.message.contains(BlankProviderCheckClientFactory.name)

        where:
        description | declared
        'null' | null
        'empty' | ''
        'blank' | '   '
    }

    // NFR-R1: a discriminator served by two discovered providers fails with a clear error naming
    //     the conflict — never an arbitrary pick between the colliding pair.
    def "a duplicate discriminator fails the registry build, naming both providers"() {
        given:
        def first = new PluginStandInCheckClientFactory()
        def second = factoryOfProvider(PluginStandInCheckClientFactory.PROVIDER)

        when:
        CheckClientDiscovery.index([first, second])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("duplicate check provider '" + PluginStandInCheckClientFactory.PROVIDER + "'")
        ex.message.contains(PluginStandInCheckClientFactory.name)
        ex.message.contains(BlankProviderCheckClientFactory.name)
    }

    def "distinct discriminators are all keyed by their own provider"() {
        given:
        def plugin = new PluginStandInCheckClientFactory()
        def other = factoryOfProvider('other')

        when:
        def registry = CheckClientDiscovery.index([plugin, other])

        then:
        registry == [(PluginStandInCheckClientFactory.PROVIDER): plugin, other: other]
    }

    def "an empty discovery pass yields an empty registry"() {
        expect:
        CheckClientDiscovery.index([]).isEmpty()
    }

    private static CheckClientFactory factoryOfProvider(String declared) {
        new BlankProviderCheckClientFactory(declared)
    }
}

/** A factory whose discriminator is supplied per instance, to drive the fail-fast rules. */
class BlankProviderCheckClientFactory implements CheckClientFactory {

    private final String declared

    BlankProviderCheckClientFactory(String declared) {
        this.declared = declared
    }

    @Override
    String provider() {
        declared
    }

    @Override
    ExternalCheckClient create(SecretsProvider secrets, Map<String, Object> subsection) {
        throw new UnsupportedOperationException()
    }
}
