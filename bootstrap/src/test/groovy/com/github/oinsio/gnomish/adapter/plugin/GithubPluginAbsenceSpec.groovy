package com.github.oinsio.gnomish.adapter.plugin

import com.github.oinsio.gnomish.adapter.check.CheckClientConfiguration
import com.github.oinsio.gnomish.adapter.check.CheckClientDiscovery
import com.github.oinsio.gnomish.adapter.tracker.TrackerAdapterConfiguration
import com.github.oinsio.gnomish.adapter.tracker.TrackerAdapterDiscovery
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import spock.lang.Shared
import spock.lang.Specification

/**
 * Removing the github jar disables every github provider, with no core source change, and the
 * factory still starts (FR12, M2 of add-plugin-architecture, github-plugin capability).
 *
 * <p>This is the acceptance test of the extraction. GitHub is a plugin only if the distribution
 * still works without it: the two github providers vanish, the providers core itself ships —
 * {@code inmemory} for the tracker port, {@code http} for the check port — remain, the registries
 * the composition root derives still build, and not one core source file has to be touched to say
 * so. The last part is checked as a source-level gate rather than by argument: if any core class
 * named a github type, removing the jar would be a compile error rather than a configuration
 * change.
 *
 * <p>The removal is staged inside this JVM by hiding the artifact's service registrations
 * ({@link GithubArtifact}), which is exactly what discovery reads.
 *
 * <p>Implements FR12, M2 of add-plugin-architecture.
 */
class GithubPluginAbsenceSpec extends Specification {

    /** A code reference to the vendor bundle: its three packages, or one of its type names. */
    private static final Pattern GITHUB_REFERENCE = ~/adapter\.tracker\.github|adapter\.check\.github|gnomish\.adapter\.github|\bGithub[A-Z]/

    /** The bundle's own directory, the one place these references belong. */
    private static final String BUNDLE_DIR = 'adapters/github/'

    /** A mis-resolved repoRoot would make the source gate pass over an empty file set. */
    private static final int KNOWN_PRODUCTION_SOURCES = 100

    @Shared
    ClassLoader withoutGithub = GithubArtifact.hiddenFrom(getClass().classLoader)

    // FR12: "the factory still starts, the github tracker and check providers are absent" — and
    //     M2's other half, the reference adapters core ships are untouched by the extraction.
    def "without the github artifact the #port port keeps its core provider and loses github"() {
        when:
        def registry = discover(withoutGithub)

        then:
        !registry.containsKey('github')
        registry.containsKey(survivor)

        and: 'the same pass with the artifact present does find github — the removal is what did it'
        discover(getClass().classLoader).containsKey('github')

        where:
        port | survivor | discover
        'tracker' | 'inmemory' | TrackerAdapterDiscovery.&discover
        'check' | 'http' | CheckClientDiscovery.&discover
    }

    // FR12: "the factory still starts" — the composition root derives a validator registry per port
    //     from the discovered set, and both still build, keyed by whatever remains.
    def "the composition root's derived registries still build without github"() {
        given:
        def trackers = TrackerAdapterDiscovery.discover(withoutGithub)
        def checks = CheckClientDiscovery.discover(withoutGithub)

        when:
        def trackerValidators = new TrackerAdapterConfiguration().trackerSubsectionValidatorRegistry(trackers)
        def checkValidators = new CheckClientConfiguration().checkParamsValidatorRegistry(checks)

        then: 'no entry survives for the absent provider, and the remaining ports are still served'
        !trackerValidators.containsKey('github')
        checkValidators.keySet() == checks.keySet()
        checkValidators.containsKey('http')
    }

    // M2: "with no core source change". Nothing outside the bundle may name a github type in code,
    //     so removing the jar is a packaging decision — never an edit to core.
    def "no production source outside the bundle names a github type"() {
        given:
        def sources = productionSources()

        expect: 'the scan really reached the source tree'
        sources.size() >= KNOWN_PRODUCTION_SOURCES

        and: 'and found no code reference to the bundle (prose in comments is not a dependency)'
        def offenders = sources.collectMany { file ->
            file.readLines().indexed(1)
            .findAll { number, line ->
                GITHUB_REFERENCE.matcher(code(line)).find()
            }
            .collect { number, line -> relative(file) + ':' + number }
        }
        offenders.isEmpty()
    }

    /** A line with its comment stripped: what the compiler actually sees. */
    private static String code(String line) {
        def trimmed = line.trim()
        if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')) {
            return ''
        }
        line.replaceFirst('//.*', '')
    }

    /** Every production source of the build except the github bundle's own. */
    private static List<File> productionSources() {
        Files.walk(repoRoot()).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }
            .map { repoRoot().relativize(it).toString() }
            .filter { it.contains('/src/main/') }
            .filter { it.endsWith('.java') || it.endsWith('.groovy') }
            .filter { !it.startsWith(BUNDLE_DIR) }
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
