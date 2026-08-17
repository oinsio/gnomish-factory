package com.github.oinsio.gnomish.adapter.tracker.inmemory

import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import spock.lang.Specification

/**
 * {@link InMemoryTrackerAdapterFactory} (task 5.15): registers a real, operator-selectable
 * {@code tracker.type: inmemory} — a fresh {@link InMemoryTracker} per invocation, no config
 * subsection or credentials required.
 *
 * <p>Implements FR1, FR3 of add-tracker-port; FR1, FR2 of add-plugin-architecture.
 */
class InMemoryTrackerAdapterFactorySpec extends Specification {

    /** Resolves nothing: this adapter reads no credential through the seam it is handed. */
    private static final SecretsProvider NO_SECRETS = { name ->
        Optional.empty()
    } as SecretsProvider

    def "create returns a fresh, empty InMemoryTracker regardless of subsection content"() {
        given:
        def factory = new InMemoryTrackerAdapterFactory()

        when:
        def tracker = factory.create(NO_SECRETS, new TrackerConfig('inmemory', 3), 'gnomish-factory-abc123')

        then:
        tracker instanceof InMemoryTracker
        tracker.listReady(10) == []
    }

    def "create returns a distinct instance on every call"() {
        given:
        def factory = new InMemoryTrackerAdapterFactory()
        def config = new TrackerConfig('inmemory', 3)

        expect:
        !factory.create(NO_SECRETS, config, 'a').is(factory.create(NO_SECRETS, config, 'b'))
    }

    def "expandRef has no short-ref scheme and always refuses"() {
        given:
        def factory = new InMemoryTrackerAdapterFactory()

        when:
        factory.expandRef(new TrackerConfig('inmemory', 3), '42')

        then:
        thrown(UnsupportedOperationException)
    }

    // D17, NFR-S1 of add-tracker-port: no credentials to declare, so the interface's default
    // empty list is inherited unmodified — nothing for the launcher to scrub for this adapter.
    def "credentialEnvVars declares nothing, inheriting the interface default"() {
        expect:
        new InMemoryTrackerAdapterFactory().credentialEnvVars(new TrackerConfig('inmemory', 3)) == []
    }

    // FR1 of add-plugin-architecture: the discovery discriminator, the key ServiceLoader-built
    // registries file this factory under.
    def "type declares the inmemory discriminator"() {
        expect:
        new InMemoryTrackerAdapterFactory().type() == 'inmemory'
    }

    // FR4, design D1/D3 of add-plugin-architecture: an opaque subsection needs no content validator,
    // so the SPI default is inherited and this provider contributes no validator-registry entry.
    def "subsectionValidator is absent, inheriting the interface default"() {
        expect:
        new InMemoryTrackerAdapterFactory().subsectionValidator().isEmpty()
    }
}
