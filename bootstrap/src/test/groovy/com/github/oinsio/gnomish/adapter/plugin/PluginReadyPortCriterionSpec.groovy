package com.github.oinsio.gnomish.adapter.plugin

import com.github.oinsio.gnomish.adapter.pipeline.TrackerDto
import com.github.oinsio.gnomish.adapter.pipeline.TrackerSeamValidator
import com.github.oinsio.gnomish.adapter.tracker.TrackerAdapterConfiguration
import com.github.oinsio.gnomish.adapter.tracker.TrackerAdapterDiscovery
import com.github.oinsio.gnomish.app.ScmBuiltInRegistry
import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors
import com.github.oinsio.gnomish.executorcontract.ExecutorName
import java.lang.reflect.Modifier
import spock.lang.Specification

/**
 * The four-point "port is plugin-ready" criterion of FR4, applied to the tracker port
 * (plugin-discovery: "A port is plugin-ready by a four-point criterion"). The check port left the
 * criterion with task 5.2 of migrate-verify-to-executor-contract: a check is a verdict executor, a
 * built-in named by core or a program the law declares, never a discovered provider. The last
 * feature assesses the check port against the same four points and finds none of them
 * (plugin-discovery: "The check port is outside the plugin-ready criterion").
 *
 * <p>The four points, one feature each:
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
 * <p>Implements FR4 of add-plugin-architecture; FR13 of migrate-verify-to-executor-contract.
 */
class PluginReadyPortCriterionSpec extends Specification {

    // FR4 (a): the discriminator is declared by the SPI itself, so discovery never has to know a
    //     provider's name from anywhere else — and FR2's half of the same point, since a factory
    //     ServiceLoader cannot instantiate is not a plugin whatever it declares.
    // Scenario: plugin/plugin-discovery / Tracker and check ports each meet all four points
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
    }

    // FR4 (b): the registry is the ServiceLoader scan — every entry traces back to a line some jar
    //     wrote in META-INF/services, and no entry exists that no jar declared.
    // Scenario: plugin/plugin-discovery / Tracker and check ports each meet all four points
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
    }

    // FR4 (c): the port has a config subsection whose content is graded by the provider's OWN
    //     validator, obtained from the discovered factory, and reported located.
    // Scenario: plugin/plugin-discovery / Tracker and check ports each meet all four points
    def "the tracker port delegates its config subsection to the selected provider's validator"() {
        given: 'a github subsection, empty enough for the provider to object'
        def trackers = TrackerAdapterDiscovery.discover(getClass().classLoader)
        def validators = new TrackerAdapterConfiguration().trackerSubsectionValidatorRegistry(trackers)

        when:
        def trackerErrors = TrackerSeamValidator.validate(
                'config.yaml', new TrackerDto('github', null, [github: [:]]), validators)

        then: 'located under the port\'s own subsection path, from github\'s validator'
        !trackerErrors.isEmpty()
        trackerErrors.every { it.where().startsWith('tracker.github') }
    }

    // FR4 (d), NFR-R1: selection is by discriminator, and a discriminator no discovered provider
    //     serves fails by name — never a silent fallback to some default vendor.
    // Scenario: plugin/plugin-discovery / Unknown provider fails with a named error
    // Scenario: plugin/plugin-discovery / Tracker and check ports each meet all four points
    def "the tracker port selects by discriminator and names a discriminator nobody serves"() {
        given:
        def trackers = TrackerAdapterDiscovery.discover(getClass().classLoader)

        expect: 'every provider is reachable under exactly the discriminator it declares'
        trackers.every { type, factory -> factory.type() == type }

        when: 'the tracker seam is pointed at a type no discovered provider serves'
        def trackerErrors = TrackerSeamValidator.validate(
                'config.yaml', new TrackerDto('nosuchvendor', null), [:])

        then:
        trackerErrors*.message().join(' ').contains("unknown tracker type 'nosuchvendor'")
    }

    // The assessment must really have run: a registry that came back empty would let every
    //     'every {}' above pass vacuously.
    def "the tracker port was assessed against a non-empty discovered set"() {
        expect:
        !TrackerAdapterDiscovery.discover(getClass().classLoader).isEmpty()
    }

    // FR13 of migrate-verify-to-executor-contract: the check port is assessed against the same four
    //     points and meets none — no SPI factory and no per-provider validator exist to discover, no
    //     registration names one, and a check selects its verdict executor by name from the executor
    //     registry: a built-in core names, or a program the law declares (the executor contract).
    // Scenario: plugin/plugin-discovery / The check port is assessed as not pluginizable
    def "the check port is assessed as not pluginizable: its extension point is the executor contract"() {
        expect: '(a) and (c): no check SPI factory and no per-provider check validator is on the classpath'
        RETIRED_CHECK_SPI.every { name ->
            getClass().classLoader.getResource(name.replace('.', '/') + '.class') == null
        }

        and: '(b): no jar registers a provider under either name, so no discovered registry can exist'
        RETIRED_CHECK_SPI.every { name ->
            !getClass().classLoader.getResources(ServiceRegistrationsHidden.SERVICES_DIR + name).hasMoreElements()
        }

        when: '(d) is replaced by the executor registry: a check resolves its executor by the name core gives it'
        def registry = ScmBuiltInRegistry.assemble()
        def entry = registry.resolve(new ExecutorName(BuiltInExecutors.SCM_STATUS))

        then: 'the built-in is core\'s own name, never a discriminator a jar declared'
        BuiltInExecutors.isBuiltIn(BuiltInExecutors.SCM_STATUS)
        entry.name() == new ExecutorName(BuiltInExecutors.SCM_STATUS)

        when: 'a name neither core gives nor the law declares'
        registry.resolve(new ExecutorName('nosuchvendor'))

        then: 'nothing on the classpath can supply it: the extension point is a law-declared program'
        def e = thrown(IllegalStateException)
        e.message.contains("no executor named 'nosuchvendor'")
    }

    /**
     * The check SPI factory and its per-provider validator, retired by task 6.1 of
     * migrate-verify-to-executor-contract: points (a) and (c) of the criterion for the check port.
     */
    private static final List<String> RETIRED_CHECK_SPI = [
        'com.github.oinsio.gnomish.app.CheckClientFactory',
        'com.github.oinsio.gnomish.app.CheckSubsectionValidator'
    ]

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
