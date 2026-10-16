package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.ModuleBuildFile
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import spock.lang.Specification

/**
 * The shared test fixtures module (FR7 of split-into-modules): consumers take it through
 * {@code testImplementation}, no production module reaches it, and no consumer keeps a copy of a
 * fixture it provides. Read as data from {@code repoRoot}, like {@link ModuleBuildFileSpec}.
 */
class TestFixturesModuleSpec extends Specification {

    // FR7 of split-into-modules: the shared fixtures reach consumers through testImplementation only
    // Scenario: build-system/test-fixtures-module / Fixtures module carries no production dependency
    def "no module declares the test fixtures on a production configuration"() {
        given:
        def modules = ModuleBuildFile.includedProjects() - [':test-fixtures']

        expect:
        modules.size() >= KNOWN_MODULES
        modules.findAll { module ->
            def code = ModuleBuildFile.textOf(module)
            ModuleBuildFile.productionDependencies(code).any {
                it.contains("project(':test-fixtures')")
            } ||
            ':test-fixtures' in ModuleBuildFile.allowedProjects(code)
        } == []
    }

    // FR7 of split-into-modules: a module takes a shared fixture from :test-fixtures through
    //     testImplementation, and keeps no copy of it in its own test tree
    // Scenario: build-system/test-fixtures-module / A module reuses shared fixtures
    def "every module importing a shared fixture declares :test-fixtures, and no test tree copies one"() {
        given: 'the shared fixtures, by fully qualified and by simple name'
        def fixtures = fixtureTypes()
        def simpleNames = fixtures.collect {
            it.substring(it.lastIndexOf('.') + 1)
        } as Set
        def testSources = RepoSourceTree.testSources()

        when: 'the modules whose specs import a fixture, and the test types named like one'
        def consumers = testSources.findAll { file ->
            fixtures.any {
                file.text.contains("import ${it}\n")
            }
        }
        .collect {
            RepoSourceTree.relative(it).with { path ->
                path.substring(0, path.indexOf('/src/test/'))
            }
        }
        .toSet()
        def copies = testSources.findAll {
            it.name.replaceFirst(/\.\w+$/, '') in simpleNames
        }

        then: 'the scan reached the fixtures and their consumers'
        fixtures.size() >= KNOWN_FIXTURES
        consumers.size() >= KNOWN_CONSUMERS

        and: 'each consumer declares the fixtures module on its test classpath'
        consumers.findAll { module ->
            !ModuleBuildFile.textOf(module).contains("testImplementation project(':test-fixtures')")
        } == [] as Set

        and: 'no shared fixture is duplicated in a consuming module'
        copies.collect { RepoSourceTree.relative(it) } == []
    }

    /** Floors for the fixture scan: a mis-resolved root would make both features vacuous. */
    private static final int KNOWN_FIXTURES = 50
    private static final int KNOWN_CONSUMERS = 5

    /** The fully qualified names of the types {@code :test-fixtures} declares. */
    private static List<String> fixtureTypes() {
        def root = RepoSourceTree.repoRoot().resolve('test-fixtures/src/main')
        Files.walk(root).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }
            .map { root.relativize(it).toString() }
            .filter { it.endsWith('.groovy') || it.endsWith('.java') }
            .map {
                it.substring(it.indexOf('/') + 1).replaceFirst(/\.\w+$/, '').replace('/', '.')
            }
            .toList()
        }
    }

    /** The main build's module count floor: a mis-parsed settings file would pass vacuously. */
    private static final int KNOWN_MODULES = 20
}
