package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.util.jar.JarFile
import spock.lang.Specification

/**
 * The japicmp gate over the published api surface (FR14, M5, design D9 of add-plugin-architecture).
 *
 * <p>Change A left japicmp report-only: the module tree was still relocating types, and there was
 * nothing released to compare against. This change is the api's first external consumer, so the
 * check flips to a gate — and a gate needs a baseline that is always there. It is committed next to
 * the module (`gnomish-plugin-api/compat-baseline/`) rather than resolved from a repository,
 * because a check that silently skips whenever its baseline is unavailable is a report with extra
 * steps, not a gate.
 *
 * <p>What this spec pins is repository DATA the gate depends on and nothing else: the committed
 * baseline jars exist and really carry this change's SPI surface, and this module is under the
 * convention that carries the gate. The gate's own arming and bite — an incompatible change failing
 * the build, an addition passing, an empty baseline failing as unarmed, the gate running under
 * {@code check} — are behavior, and are proven by executing the gate convention in an isolated
 * TestKit build: {@code build-logic}'s {@code ApiCompatibilityGateFunctionalSpec} (FR1-FR6 of
 * add-functional-api-gate-test), which runs on every {@code :build-logic:check}. Nothing here
 * asserts the text of a convention script any more: that verified wording, not behavior, and broke
 * on harmless refactors while missing real disarming (FR8).
 *
 * <p>Lives in {@code :bootstrap} for the same reason as {@link ModuleBuildFileSpec}: it is a
 * whole-tree gate, and this is the module whose {@code test} task wires {@code repoRoot} and
 * declares the build scripts as inputs.
 */
class ApiCompatibilityGateSpec extends Specification {

    /** The SPI types this change ships; a baseline without them is not this change's surface. */
    private static final List<String> SHIPPED_SPI = [
        'com/github/oinsio/gnomish/app/CheckClientFactory.class',
        'com/github/oinsio/gnomish/app/CheckParamsValidator.class',
        'com/github/oinsio/gnomish/app/CheckSubsectionValidator.class',
        'com/github/oinsio/gnomish/app/CheckRunContext.class',
        'com/github/oinsio/gnomish/app/ConnectionProfiles.class',
        'com/github/oinsio/gnomish/app/TrackerAdapterFactory.class',
        'com/github/oinsio/gnomish/app/TrackerSubsectionValidator.class',
        'com/github/oinsio/gnomish/app/port/check/ExternalCheckPinContributor.class'
    ]

    // FR14: the baseline is committed, so the gate is armed on a fresh clone and in CI — not only on
    //     a machine that happens to have published the artifact locally.
    def "the committed baseline carries both halves of the semver surface"() {
        given:
        def jars = baselineJars()

        expect: 'the api jar and the :domain jar it re-exposes through its api dependency (design D4)'
        jars*.name.any { it.startsWith('gnomish-plugin-api-') }
        jars*.name.any { it.startsWith('domain-') }

        and: 'both are real archives, not placeholders'
        jars.every { it.length() > 0 }
    }

    // FR14, design D9: "armed against the baseline this change ships" — the SPI added across groups
    //     1-7 must be IN the baseline, or the gate would be guarding a surface that no longer exists.
    def "the baseline contains this change's SPI surface"() {
        given:
        def apiJar = baselineJars().find {
            it.name.startsWith('gnomish-plugin-api-')
        }

        when:
        def entries = new JarFile(apiJar).withCloseable { jar ->
            jar.entries().collect {
                it.name
            }
        }

        then:
        SHIPPED_SPI.every { entries.contains(it) }
    }

    // M5, FR8 of add-functional-api-gate-test: the module really is under the convention that
    //     carries the gate. Which is a fact about this build file — data; that the gate BITES is
    //     behavior, proven by executing it (see the javadoc above).
    def "the published api module applies the published-api convention"() {
        given:
        def buildFile = publishedApiBuildFile().text

        expect:
        buildFile =~ /id 'published-api-conventions'/
    }

    private static List<File> baselineJars() {
        def dir = RepoSourceTree.repoRoot().resolve('gnomish-plugin-api/compat-baseline')
        assert Files.isDirectory(dir): "no committed api baseline at ${dir} — the gate cannot be armed (FR14)"
        Files.list(dir).withCloseable { paths ->
            paths.filter {
                it.fileName.toString().endsWith('.jar')
            }.map {
                it.toFile()
            }.toList()
        }
    }

    private static File publishedApiBuildFile() {
        def file = RepoSourceTree.repoRoot().resolve('gnomish-plugin-api/build.gradle')
        assert Files.isRegularFile(file): "missing ${file}"
        file.toFile()
    }
}
