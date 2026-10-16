package com.github.oinsio.gnomish.architecture

import static com.github.oinsio.gnomish.architecture.SecurityOverrideSources.buildscriptForces
import static com.github.oinsio.gnomish.architecture.SecurityOverrideSources.catalogHeader
import static com.github.oinsio.gnomish.architecture.SecurityOverrideSources.catalogOverrides
import static com.github.oinsio.gnomish.architecture.SecurityOverrideSources.catalogPins
import static com.github.oinsio.gnomish.architecture.SecurityOverrideSources.covers
import static com.github.oinsio.gnomish.architecture.SecurityOverrideSources.section

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/**
 * Security version overrides against the committed lock state OSV-Scanner reads (FR3, FR4, FR5,
 * NFR-S1, NFR-R1, NFR-R2 of fix-osv-dependency-gate). An override lives in one version source — the
 * catalog's security-override block for the modules, {@code gradle.properties} for a buildscript
 * classpath, applied by a {@code force} in that {@code build.gradle} — and is effective only as far
 * as the regenerated lockfiles record it. The scan is authoritative only over regenerated lock
 * state, so a lockfile that still lists another version is a stale entry these features name.
 */
class SecurityOverrideLockStateSpec extends Specification {

    static final String ADVISORY = /GHSA(-[0-9a-z]{4}){3}/

    // Scenario: build-system/quality-gates / A security override names its advisory and its exit
    def "FR3: every security override names the advisory it clears, and its block says when to drop it"() {
        given:
        def catalog = catalogOverrides()
        def properties = Files.readAllLines(root().resolve('gradle.properties'))
        def bullets = section(properties, '# Security overrides forcing CVE fixes', 'Drop each once')

        expect: 'the catalog block: one advisory per entry, one exit for the block'
        catalog.size() >= 5
        catalog.every { name, entry -> entry.comment =~ ADVISORY }
        catalogHeader().any { it.contains('Drop each once') }

        and: 'the buildscript block: an advisory for every overridden artifact, and its exit'
        def forced = buildscriptForces()
        !forced.isEmpty()
        bullets.last().contains('Drop each once')
        forced.every { force ->
            bullets.any {
                it =~ ADVISORY && covers(it.replaceFirst(/^#\s*-\s*/, '').takeWhile {
                    it != ':'
                }, force.module)
            }
        }
    }

    // Scenario: build-system/quality-gates / A security override names its advisory and its exit
    // Scenario: build-system/quality-gates / The scan reads regenerated lock state
    def "FR4, NFR-R1: the lockfiles an override is applied to record exactly the version its single source pins"() {
        expect: 'every module lockfile of the main build carries the catalog\'s version'
        def moduleLocks = lockfiles().findAll {
            it.fileName.toString() == 'gradle.lockfile' && !separateBuild(it)
        }
        moduleLocks.size() >= 15
        stale(catalogPins(), moduleLocks) == []

        and: 'every buildscript lockfile carries the gradle.properties version its build.gradle forces'
        buildscriptForces().groupBy {
            it.lockfile
        }.collectMany { lock, forces ->
            stale(forces.collectEntries { [(it.module): it.version] }, [lock])
        } == []
    }

    // Scenario: build-system/quality-gates / An override reaches every configuration that resolves the artifact
    def "FR4: an override reaches every committed lockfile listing its artifact, the separate builds included"() {
        expect: 'a lockfile the override does not reach is named as a stale entry'
        stale(catalogPins(), lockfiles()) == []
    }

    // F112: scenario text aligned to what this test asserts
    // Scenario: build-system/quality-gates / A production-scope advisory is fixed, never suppressed
    def "NFR-S1: an advisory a production-scope override clears is allowlisted only for a metadata-only candidate no lockfile resolves"() {
        given: 'the advisories of the overrides whose artifact sits on a production runtime classpath'
        def locks = lockLines(lockfiles())
        def production = catalogOverrides().findAll { name, entry ->
            lockLines(lockfiles().findAll { !separateBuild(it) }).any { line ->
                entry.modules.any { line.startsWith("${it}:") } &&
                !line.tokenize('=').last().tokenize(',').intersect([
                    'runtimeClasspath',
                    'productionRuntimeClasspath'
                ]).isEmpty()
            }
        }
        def advisories = production.collectMany { name, entry ->
            (entry.comment =~ ADVISORY).collect {
                it[0]
            }
        } as Set
        def reasons = (Files.readString(root().resolve('osv-scanner.toml')) =~ /id = "(GHSA[^"]+)"[^\[]*?reason = "([^"]*)"/).collect {
            [it[1], it[2]]
        }.collectEntries()

        expect: 'the scan found the production overrides, none of the test-scope ones, and the allowlist'
        production.keySet().containsAll(['jackson', 'logback', 'log4j'])
        !production.containsKey('httpclient5')
        reasons.size() > 10

        and: 'each of their allowlisted advisories names a metadata-only candidate that no lockfile resolves'
        advisories.findAll { reasons.containsKey(it) }.findAll { id ->
            def candidate = reasons[id] =~ /^([\w.-]+) (\d+(?:\.\d+)+): metadata-only superseded candidate/
            !candidate.find() || locks.any {
                it.contains(":${candidate.group(1)}:${candidate.group(2)}=")
            }
        } == [] as Set
    }

    /** Lines {@code group:artifact:version=configurations} whose version differs from the pin, with their file. */
    private static List<String> stale(Map<String, String> pins, List<Path> locks) {
        locks.collectMany { lock ->
            Files.readAllLines(lock).findAll { line ->
                pins.any { module, version ->
                    line.startsWith("${module}:") && !line.startsWith("${module}:${version}=")
                }
            }.collect {
                "${RepoSourceTree.repoRoot().relativize(lock)}: ${it}".toString()
            }
        }
    }

    private static List<String> lockLines(List<Path> locks) {
        locks.collectMany { Files.readAllLines(it) }
    }

    /** Every committed lockfile, build output excluded. */
    private static List<Path> lockfiles() {
        Files.walk(root()).withCloseable { paths ->
            paths.filter { it.fileName.toString().endsWith('gradle.lockfile') }
            .filter {
                !root().relativize(it).any {
                    it.toString() in [
                        'build',
                        '.gradle',
                        'node_modules'
                    ]
                }
            }
            .toList()
        }
    }

    /** The included builds lock on their own: the catalog's platform and forces do not apply to them. */
    private static boolean separateBuild(Path lock) {
        def relative = root().relativize(lock).toString()
        relative.startsWith('build-logic/') || relative.startsWith('build-checks/') || relative.endsWith('buildscript-gradle.lockfile')
    }

    private static Path root() {
        RepoSourceTree.repoRoot()
    }
}
