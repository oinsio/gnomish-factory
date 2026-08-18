package com.github.oinsio.gnomish.adapter.tracker

import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link TrackerAdapterDiscovery}: the tracker port's registry is built by {@code ServiceLoader},
 * and it is built deterministically.
 *
 * <p>The first feature is the plugin promise itself (FR1): a factory declared in a
 * {@code META-INF/services} entry the build ships nowhere — staged here on a class loader of its
 * own, exactly as a third-party jar would carry it — becomes selectable, and no core source file
 * mentions it. The remaining features are the fail-fast rules (NFR-R1): a blank or absent
 * discriminator and a collision both abort the registry build with an error naming the offenders,
 * at startup rather than at first use.
 *
 * <p>Implements FR1, FR2 of add-plugin-architecture; NFR-R1 of add-plugin-architecture.
 */
class TrackerAdapterDiscoverySpec extends Specification {

    @TempDir
    Path pluginJarRoot

    // FR1: "a jar exposing a tracker SPI factory via META-INF/services is present on the classpath
    // → the factory discovers the provider and its discriminator becomes selectable, with no change
    // to any core source file".
    def "a service entry the core names nowhere is discovered and becomes selectable"() {
        given: 'a plugin "jar": a service entry naming a factory no core source references'
        Path services = pluginJarRoot.resolve('META-INF/services')
        Files.createDirectories(services)
        Files.writeString(
                services.resolve(TrackerAdapterFactory.name),
                PluginStandInTrackerAdapterFactory.name + '\n')
        def loader = new URLClassLoader(
                [pluginJarRoot.toUri().toURL()] as URL[], getClass().classLoader)

        when:
        def registry = TrackerAdapterDiscovery.discover(loader)

        then: 'the plugin is selectable beside the two providers the build itself ships'
        registry[PluginStandInTrackerAdapterFactory.TYPE] instanceof PluginStandInTrackerAdapterFactory
        registry.keySet().containsAll([
            'github',
            'inmemory',
            PluginStandInTrackerAdapterFactory.TYPE
        ])

        cleanup:
        loader.close()
    }

    // FR2, design D2: ServiceLoader instantiates through the public no-arg constructor, so a
    // discovered factory holds no injected SecretsProvider — it receives one per create(...) call.
    def "discovery builds every provider through its public no-arg constructor"() {
        given:
        def registry = TrackerAdapterDiscovery.discover()

        expect:
        registry.every { type, factory ->
            factory.class.getConstructor() != null
        }
    }

    // NFR-R1: a provider with no usable discriminator cannot be keyed, so the build stops with an
    // error naming it rather than filing it under a blank key nothing can select.
    def "a #description discriminator fails the registry build, naming the provider"() {
        when:
        TrackerAdapterDiscovery.index([factoryOfType(declared)])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains('tracker')
        ex.message.contains('declares no type() discriminator')
        ex.message.contains(BlankTypeTrackerAdapterFactory.name)

        where:
        description | declared
        'null' | null
        'empty' | ''
        'blank' | '   '
    }

    // NFR-R1: "a discriminator served by two discovered providers fails with a clear error naming
    // the conflict" — never an arbitrary pick between the colliding pair.
    def "a duplicate discriminator fails the registry build, naming both providers"() {
        given:
        def first = new PluginStandInTrackerAdapterFactory()
        def second = factoryOfType(PluginStandInTrackerAdapterFactory.TYPE)

        when:
        TrackerAdapterDiscovery.index([first, second])

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("duplicate tracker provider '" + PluginStandInTrackerAdapterFactory.TYPE + "'")
        ex.message.contains(PluginStandInTrackerAdapterFactory.name)
        ex.message.contains(BlankTypeTrackerAdapterFactory.name)
    }

    def "distinct discriminators are all keyed by their own type"() {
        given:
        def plugin = new PluginStandInTrackerAdapterFactory()
        def other = factoryOfType('other')

        when:
        def registry = TrackerAdapterDiscovery.index([plugin, other])

        then:
        registry == [(PluginStandInTrackerAdapterFactory.TYPE): plugin, other: other]
    }

    def "an empty discovery pass yields an empty registry"() {
        expect:
        TrackerAdapterDiscovery.index([]).isEmpty()
    }

    private static TrackerAdapterFactory factoryOfType(String declared) {
        new BlankTypeTrackerAdapterFactory(declared)
    }
}

/** A factory whose discriminator is supplied per instance, to drive the fail-fast rules. */
class BlankTypeTrackerAdapterFactory implements TrackerAdapterFactory {

    private final String declared

    BlankTypeTrackerAdapterFactory(String declared) {
        this.declared = declared
    }

    @Override
    String type() {
        declared
    }

    @Override
    Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId) {
        throw new UnsupportedOperationException()
    }

    @Override
    TaskRef expandRef(TrackerConfig config, String rawRef) {
        throw new UnsupportedOperationException()
    }
}
