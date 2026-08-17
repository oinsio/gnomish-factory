package com.github.oinsio.gnomish.architecture

import java.nio.file.Files
import java.nio.file.Path
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
 * <p>What this spec pins is the arming, in the two halves that can rot independently: the baseline
 * jars exist and really carry this change's SPI surface, and the convention that consumes them is
 * configured to FAIL rather than report. The gate's own bite — a deliberately incompatible change
 * breaking the build — is japicmp's behavior over that configuration, verified against these very
 * artifacts while applying task 7.3 (removing {@code CheckClientFactory.provider()} fails
 * {@code japicmpApiGate} with "Detected binary changes"; restoring it passes).
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

    // M5: the flip itself. Report-only wording and non-failing flags are exactly what this change
    //     supersedes, so they must not survive in the convention that runs on every `check`.
    def "the published-api convention is armed to fail, not to report"() {
        given:
        def convention = conventionFile().text

        expect: 'binary-incompatible changes fail the build'
        convention.contains('failOnModification = true')
        convention.contains('onlyBinaryIncompatibleModified = true')

        and: 'the gate runs as part of check, under a name that says what it does'
        convention.contains("dependsOn tasks.named('japicmpApiGate')")

        and: 'the superseded report-only arming is gone'
        !convention.contains('failOnModification = false')
        !convention.contains('japicmpReport')
    }

    private static List<File> baselineJars() {
        def dir = repoRoot().resolve('gnomish-plugin-api/compat-baseline')
        assert Files.isDirectory(dir): "no committed api baseline at ${dir} — the gate cannot be armed (FR14)"
        Files.list(dir).withCloseable { paths ->
            paths.filter {
                it.fileName.toString().endsWith('.jar')
            }.map {
                it.toFile()
            }.toList()
        }
    }

    private static File conventionFile() {
        def file = repoRoot().resolve('build-logic/src/main/groovy/published-api-conventions.gradle')
        assert Files.isRegularFile(file): "missing ${file}"
        file.toFile()
    }

    private static Path repoRoot() {
        def root = Path.of(System.getProperty('repoRoot'))
        assert Files.isDirectory(root): 'repoRoot system property is not set (see bootstrap/verification.gradle)'
        root
    }
}
