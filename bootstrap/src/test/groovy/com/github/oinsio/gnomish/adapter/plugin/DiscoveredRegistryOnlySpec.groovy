package com.github.oinsio.gnomish.adapter.plugin

import com.github.oinsio.gnomish.adapter.check.CheckClientDiscovery
import com.github.oinsio.gnomish.adapter.tracker.TrackerAdapterDiscovery
import com.github.oinsio.gnomish.app.CheckClientFactory
import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import spock.lang.Shared
import spock.lang.Specification

/**
 * No hardwired provider registry survives anywhere: every provider resolution goes through {@code
 * ServiceLoader} (M1, FR1 of add-plugin-architecture).
 *
 * <p>M1's own wording is a grep — "zero hardwired {@code Map.of(...)} provider registries remain" —
 * but a grep alone is the weakest of the three ways to say it, and on this codebase the literal one
 * is also the noisiest: {@code Map.of(} appears dozens of times in production source as an empty
 * default or an environment map, and once inside a javadoc sentence describing the registry that was
 * removed. So the claim is made three times, narrowest last:
 *
 * <ol>
 *   <li><b>Structural.</b> No production class <em>constructs</em> an SPI factory. A hardwired
 *       registry cannot exist without one, whatever it is named or spelled, so this survives any
 *       refactor a regex would miss.
 *   <li><b>Behavioural.</b> With every {@code META-INF/services} registration for a port's SPI
 *       hidden, that port's registry comes back empty. A fallback of any shape would answer
 *       otherwise.
 *   <li><b>Literal.</b> The grep M1 names, over comment-stripped production source, restricted to
 *       the shape that would actually be a registry.
 * </ol>
 *
 * <p>Implements FR1, M1 of add-plugin-architecture.
 */
class DiscoveredRegistryOnlySpec extends Specification {

    /** The declaration shape of a hardwired registry: a map from discriminator to an SPI type. */
    private static final Pattern HARDWIRED_REGISTRY = ~/(?s)Map\s*<\s*String\s*,\s*(?:TrackerAdapterFactory|CheckClientFactory|TrackerSubsectionValidator|CheckSubsectionValidator|CheckParamsValidator)\s*>[^;]{0,300}?=\s*Map\s*\.\s*(?:of|ofEntries)\s*\(/

    /** A mis-resolved repoRoot would make the source gate pass over an empty file set. */
    private static final int KNOWN_PRODUCTION_SOURCES = 100

    @Shared
    JavaClasses productionClasses = new ClassFileImporter()
    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
    .importPackages('com.github.oinsio.gnomish')

    // M1, FR1: the structural form of the claim. Every discovered factory is built reflectively by
    //     ServiceLoader, which ArchUnit cannot see — so a production `new SomeCheckClientFactory()`
    //     is, by construction, someone assembling a registry by hand.
    def "no production class constructs a #spi.simpleName"() {
        when:
        def offenders = productionClasses.collectMany { javaClass ->
            javaClass.constructorCallsFromSelf
            .findAll { call ->
                call.targetOwner != javaClass && call.targetOwner.isAssignableTo(spi)
            }
            .collect { call ->
                javaClass.name + ' -> new ' + call.targetOwner.name + '()'
            }
        }

        then:
        offenders.isEmpty()

        and: 'the scan really imported production bytecode'
        productionClasses.size() >= KNOWN_PRODUCTION_SOURCES

        where:
        spi << [
            TrackerAdapterFactory,
            CheckClientFactory
        ]
    }

    // M1: the behavioural form, and the sharpest of the three. Hide every registration of one port's
    //     SPI — not one artifact's, as GithubArtifact does, but all of them — and the registry must
    //     come back empty. Anything a core file still knew about would show up here.
    def "with every #port service registration hidden the registry is empty"() {
        given:
        def blinded = ServiceRegistrationsHidden.of(spi, getClass().classLoader)

        expect: 'the same pass with the registrations visible finds providers — the hiding is what did it'
        !discover(getClass().classLoader).isEmpty()

        and: 'and with nothing declared anywhere, nothing is resolvable'
        discover(blinded).isEmpty()

        where:
        port | spi | discover
        'tracker' | TrackerAdapterFactory | TrackerAdapterDiscovery.&discover
        'check' | CheckClientFactory | CheckClientDiscovery.&discover
    }

    // M1, verbatim: "zero hardwired Map.of(...) provider registries remain". Comments are stripped
    //     first — TrackerAdapterConfiguration's own javadoc says the words while describing what it
    //     no longer does, and prose is not a registry.
    def "no production source declares a Map.of provider registry"() {
        given:
        def sources = productionSources()

        expect: 'the scan really reached the source tree'
        sources.size() >= KNOWN_PRODUCTION_SOURCES

        and:
        def offenders = sources.findAll { file ->
            HARDWIRED_REGISTRY.matcher(code(file)).find()
        }
        .collect { relative(it) }
        offenders.isEmpty()
    }

    /** A file's source with every comment removed: what the compiler actually sees. */
    private static String code(File file) {
        file.readLines()
                .collect { line ->
                    def trimmed = line.trim()
                    trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')
                            ? ''
                            : line.replaceFirst('//.*', '')
                }
                .join('\n')
    }

    /** Every production source of the build. */
    private static List<File> productionSources() {
        Files.walk(repoRoot()).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }
            .map { repoRoot().relativize(it).toString() }
            .filter { it.contains('/src/main/') }
            .filter { it.endsWith('.java') || it.endsWith('.groovy') }
            .map { repoRoot().resolve(it).toFile() }
            .toList()
        }
    }

    private static Path repoRoot() {
        def root = Path.of(System.getProperty('repoRoot'))
        assert Files.isDirectory(root): 'repoRoot system property is not set (see bootstrap/verification.gradle)'
        root
    }

    private static String relative(File file) {
        repoRoot().relativize(file.toPath()).toString()
    }
}
