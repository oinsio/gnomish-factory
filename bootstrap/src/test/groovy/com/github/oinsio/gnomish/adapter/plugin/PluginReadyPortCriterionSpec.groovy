package com.github.oinsio.gnomish.adapter.plugin

import com.github.oinsio.gnomish.adapter.check.CheckClientDiscovery
import com.github.oinsio.gnomish.adapter.check.CheckProviderSeam
import com.github.oinsio.gnomish.adapter.check.ProviderDispatchingExternalCheckClient
import com.github.oinsio.gnomish.adapter.pipeline.TrackerDto
import com.github.oinsio.gnomish.adapter.pipeline.TrackerSeamValidator
import com.github.oinsio.gnomish.adapter.tracker.TrackerAdapterConfiguration
import com.github.oinsio.gnomish.adapter.tracker.TrackerAdapterDiscovery
import com.github.oinsio.gnomish.app.CheckClientFactory
import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.lang.reflect.Modifier
import java.time.Duration
import spock.lang.Specification

/**
 * The four-point "port is plugin-ready" criterion of FR4, applied independently to the two ports
 * this change claims for it (plugin-discovery: "A port is plugin-ready by a four-point criterion").
 *
 * <p>The four points, one feature each, both ports asserted in every one:
 *
 * <ol>
 *   <li><b>(a)</b> an SPI factory exposing a {@code type()} / {@code provider()} discriminator —
 *       and, since {@code ServiceLoader} is the thing that must build it, a public no-arg
 *       constructor (FR2);
 *   <li><b>(b)</b> a {@code ServiceLoader}-backed registry — the registry <em>is</em> the service
 *       scan, nothing more and nothing less;
 *   <li><b>(c)</b> a config subsection plus a per-provider SPI validator, reached at the port's own
 *       load seam and reported as a located {@code ConfigError};
 *   <li><b>(d)</b> discriminator-based selection, with a named failure for a discriminator no
 *       discovered provider serves (NFR-R1) — never a silent fallback.
 * </ol>
 *
 * <p>This is an assessment, not a boundary rule, which is why it sits beside the other discovery
 * specs rather than in {@code ..architecture}. That a registry holds no hardwired entry is the
 * separate claim of {@link DiscoveredRegistryOnlySpec}; point (b) here only asserts the positive
 * half — every entry traces back to a {@code META-INF/services} line.
 *
 * <p>Implements FR4 of add-plugin-architecture.
 */
class PluginReadyPortCriterionSpec extends Specification {

    private static final SecretsProvider NO_SECRETS = { name ->
        Optional.empty()
    } as SecretsProvider

    // FR4 (a): the discriminator is declared by the SPI itself, so discovery never has to know a
    //     provider's name from anywhere else — and FR2's half of the same point, since a factory
    //     ServiceLoader cannot instantiate is not a plugin whatever it declares.
    def "the #port SPI declares a #discriminator discriminator every provider answers"() {
        given:
        def method = spi.getMethod(discriminator)

        expect: 'a public, no-arg, String-returning discriminator on the interface'
        method.returnType == String
        Modifier.isPublic(method.modifiers)

        and: 'every discovered provider answers it with a non-blank value, and is ServiceLoader-buildable'
        def registry = discover(getClass().classLoader)
        !registry.isEmpty()
        registry.values().every { factory ->
            !method.invoke(factory).isBlank() && Modifier.isPublic(factory.class.getDeclaredConstructor().modifiers)
        }

        where:
        port | spi | discriminator | discover
        'tracker' | TrackerAdapterFactory | 'type' | TrackerAdapterDiscovery.&discover
        'check' | CheckClientFactory | 'provider' | CheckClientDiscovery.&discover
    }

    // FR4 (b): the registry is the ServiceLoader scan — every entry traces back to a line some jar
    //     wrote in META-INF/services, and no entry exists that no jar declared.
    def "the #port registry holds exactly the providers META-INF/services declares"() {
        given:
        def registry = discover(getClass().classLoader)

        expect:
        registry.values().collect {
            it.class.name
        }.toSet() == registeredClassNames(spi)

        where:
        port | spi | discover
        'tracker' | TrackerAdapterFactory | TrackerAdapterDiscovery.&discover
        'check' | CheckClientFactory | CheckClientDiscovery.&discover
    }

    // FR4 (c): each port has an operator/repo config subsection whose content is graded by the
    //     provider's OWN validator, obtained from the discovered factory, and reported located.
    def "each port delegates its config subsection to the selected provider's validator"() {
        given: 'a github subsection on each port, empty enough for the provider to object'
        def trackers = TrackerAdapterDiscovery.discover(getClass().classLoader)
        def validators = new TrackerAdapterConfiguration().trackerSubsectionValidatorRegistry(trackers)
        def checks = CheckClientDiscovery.discover(getClass().classLoader)

        when:
        def trackerErrors = TrackerSeamValidator.validate(
                'config.yaml', new TrackerDto('github', null, [github: [:]]), validators)
        def checkErrors = CheckProviderSeam.validate('application.yaml', [github: [:]], checks)

        then: 'both are located under the port\'s own subsection path, and both came from github\'s validator'
        !trackerErrors.isEmpty()
        trackerErrors.every { it.where().startsWith('tracker.github') }

        and:
        !checkErrors.isEmpty()
        checkErrors.every { it.where().startsWith('factory.check.github') }
    }

    // FR4 (d), NFR-R1: selection is by discriminator, and a discriminator no discovered provider
    //     serves fails by name on both ports — never a silent fallback to some default vendor.
    def "each port selects by discriminator and names a discriminator nobody serves"() {
        given:
        def trackers = TrackerAdapterDiscovery.discover(getClass().classLoader)
        def checks = CheckClientDiscovery.discover(getClass().classLoader)

        expect: 'every provider is reachable under exactly the discriminator it declares'
        trackers.every { type, factory -> factory.type() == type }
        checks.every { provider, factory -> factory.provider() == provider }

        when: 'the tracker seam is pointed at a type no discovered provider serves'
        def trackerErrors = TrackerSeamValidator.validate(
                'config.yaml', new TrackerDto('nosuchvendor', null), [:])

        then:
        trackerErrors*.message().join(' ').contains("unknown tracker type 'nosuchvendor'")

        when: 'a check selects a provider no discovered jar serves'
        new ProviderDispatchingExternalCheckClient(checks, [:], NO_SECRETS)
        .poll(new VerifyCheck.External('gate', 'nosuchvendor', Duration.ofSeconds(1),
        Duration.ofSeconds(1), VerifyCheck.TimeoutClass.QUALITY), null)

        then:
        def failure = thrown(IllegalStateException)
        failure.message.contains('nosuchvendor')
        failure.message.contains('discovered providers:')
    }

    // The assessment must really have run: two ports, each with providers discovered. A registry
    //     that came back empty would let every 'every {}' above pass vacuously.
    def "both ports were assessed against a non-empty discovered set"() {
        expect:
        !TrackerAdapterDiscovery.discover(getClass().classLoader).isEmpty()
        !CheckClientDiscovery.discover(getClass().classLoader).isEmpty()
    }

    /** The provider class names every {@code META-INF/services} entry for {@code spi} declares. */
    private Set<String> registeredClassNames(Class<?> spi) {
        getClass().classLoader.getResources(ServiceRegistrationsHidden.SERVICES_DIR + spi.name)
                .toList()
                .collectMany { url -> url.text.readLines() }
                .collect { it.replaceFirst('#.*', '').trim() }
                .findAll { !it.isEmpty() }
                .toSet()
    }
}
