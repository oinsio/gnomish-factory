package com.github.oinsio.gnomish.adapter.github

import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory
import com.github.oinsio.gnomish.adapter.tracker.github.GithubTrackerAdapterFactory
import com.github.oinsio.gnomish.app.CheckClientFactory
import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import java.lang.reflect.Modifier
import spock.lang.Specification

/**
 * The github bundle is packaged as a plugin jar over a private HTTP core (FR12, design D7 of
 * add-plugin-architecture, github-plugin capability).
 *
 * <p>Two claims, both about what leaves this jar. First, the jar exports exactly two things — the
 * tracker SPI factory and the check SPI factory — through {@code META-INF/services}, each
 * instantiable the way {@code ServiceLoader} instantiates one; every other class in the bundle,
 * including the shared HTTP core in {@code adapter.github} (client, retry config, rate-limit
 * accounting, conditional-request cache), is reachable only from inside. Second, that core does not
 * leak out through the exported factories' own signatures either: a consumer of this jar sees SPI
 * types, never a github HTTP type — which is what makes the core replaceable without an api change
 * and what the "built over a private core that is NOT part of gnomish-plugin-api" wording asks for.
 *
 * <p>Implements FR12 of add-plugin-architecture.
 */
class GithubPluginPackagingSpec extends Specification {

    /** The bundle's private HTTP core: shared by the two adapters here, exported to nobody. */
    private static final String PRIVATE_CORE_PACKAGE = 'com.github.oinsio.gnomish.adapter.github'

    // FR12: "the plugin SHALL expose only its SPI factories and validators through
    //     META-INF/services" — one entry per port, naming the factory and nothing else.
    def "the jar registers exactly one #spi provider: #expected"() {
        when:
        def registered = serviceEntries(spi)

        then:
        registered == [expected.name]

        where:
        spi | expected
        TrackerAdapterFactory | GithubTrackerAdapterFactory
        CheckClientFactory | GithubCheckClientFactory
    }

    // FR12, FR2: a registered class must be loadable by ServiceLoader exactly as a third-party
    //     jar's would be — public, implementing the SPI, with a public no-arg constructor.
    def "every registered #spi provider is ServiceLoader-instantiable"() {
        given:
        def registered = serviceEntries(spi).collect { Class.forName(it) }

        expect:
        registered.every { spi.isAssignableFrom(it) }
        registered.every { Modifier.isPublic(it.modifiers) }
        registered.every { Modifier.isPublic(it.getConstructor().modifiers) }

        where:
        spi << [
            TrackerAdapterFactory,
            CheckClientFactory
        ]
    }

    // FR12: the shared HTTP core is an internal of the bundle. Registering one would export it
    //     under an SPI it does not serve; the point of the check is that the export list is the
    //     SPI list, not "whatever the bundle happens to contain".
    def "no private-core type is registered as a service"() {
        given:
        def registered = serviceEntries(TrackerAdapterFactory) + serviceEntries(CheckClientFactory)

        expect: 'the core exists as a package, and no entry names anything inside it'
        GithubHttpClient.name.startsWith(PRIVATE_CORE_PACKAGE)
        registered.every { !it.startsWith(PRIVATE_CORE_PACKAGE + '.') }
    }

    // FR12: "its shared HTTP client, rate-limit, cache, and retry internals SHALL stay private to
    //     the jar" — not merely unregistered, but absent from what the exported factories hand out
    //     or ask for, which is the only other way a core type could reach a consumer.
    def "the private core does not appear on #factory's exported signatures"() {
        given: 'every type the factory takes or returns on a public member'
        def exported = (factory.methods.toList() + factory.constructors.toList())
                .findAll { Modifier.isPublic(it.modifiers) }
                .collectMany { member ->
                    member.parameterTypes.toList() + (member.hasProperty('returnType') ? [member.returnType] : [])
                }
                .collect { it.componentType ?: it }
                .findAll { it.package != null }

        expect: 'the scan really saw the factory surface, and none of it is a private-core type'
        !exported.isEmpty()
        exported.every { it.package.name != PRIVATE_CORE_PACKAGE }

        where:
        factory << [
            GithubTrackerAdapterFactory,
            GithubCheckClientFactory
        ]
    }

    /** The class names a {@code META-INF/services} file registers, comments and blanks dropped. */
    private static List<String> serviceEntries(Class<?> spi) {
        def resource = GithubPluginPackagingSpec.getResource('/META-INF/services/' + spi.name)
        assert resource != null: 'no service registration for ' + spi.name
        resource.text.readLines()
                .collect { it.replaceAll('#.*', '').trim() }
                .findAll { !it.isEmpty() }
    }
}
