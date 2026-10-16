package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/**
 * One owner per build concern, read from the build scripts and the test trees as data (FR6 of
 * split-into-modules; FR8 of add-functional-api-gate-test; FR3 of add-project-license). Lives in
 * {@code :bootstrap} beside {@link ModuleBuildFileSpec}, whose {@code test} task wires
 * {@code repoRoot} and declares the build scripts as inputs.
 */
class BuildScriptOwnerSpec extends Specification {

    /** A dependency spelled with its own version: {@code 'group:name:version'}. */
    private static final String VERSIONED_COORDINATE = /['"][\w.-]+:[\w.-]+:[\w.+-]+['"]/

    /**
     * The one script outside the version catalog's reach: {@code build-logic}'s own build file pins
     * the japicmp plugin's hardcoded worker coordinates for the TestKit cache, deliberately unlocked
     * and not a dependency of any module (its comment above {@code japicmpWorkerRuntime}).
     */
    private static final String WORKER_PINS = 'build-logic/build.gradle'

    /** The one convention that names the root license and notice as jar content. */
    private static final String TERMS_CONVENTION = 'build-logic/src/main/groovy/distribution-terms-conventions.gradle'

    /** The api-gate convention scripts, whose text no test may read (FR8 of add-functional-api-gate-test). */
    private static final List<String> GATE_SCRIPTS = [
        'api-compatibility-gate-conventions',
        'published-api-conventions'
    ]
    .collect {
        it + '.gradle'
    } // spelled apart, so this spec's own source names no gate script

    // FR6 of split-into-modules: every external version is declared once, in the shared catalog
    // Scenario: build-system/build-conventions / Version catalog is shared
    def "no build script declares a dependency with a version of its own"() {
        given:
        def scripts = gradleScripts().findAll {
            RepoSourceTree.relative(it) != WORKER_PINS
        }

        expect: 'the scan reached the build, and its modules declare through the catalog'
        scripts.size() >= KNOWN_SCRIPTS
        scripts.count {
            RepoSourceTree.code(it).contains('libs.')
        } >= KNOWN_SCRIPTS / 2

        and: 'no script spells a versioned coordinate'
        scripts.findAll {
            RepoSourceTree.code(it) =~ VERSIONED_COORDINATE
        }.collect {
            RepoSourceTree.relative(it)
        } == []
    }

    // FR3 of add-project-license (design D6): one owner of "the root files are jar content"
    // Scenario: release/project-licensing / One build convention names the terms as jar content
    def "only the distribution-terms convention names LICENSE and NOTICE as jar content, and the shipped modules apply it"() {
        given:
        def naming = gradleScripts().findAll { script ->
            def code = RepoSourceTree.code(script)
            code.contains('metaInf') && (code.contains('LICENSE') || code.contains('NOTICE'))
        }

        expect:
        naming.collect { RepoSourceTree.relative(it) } == [TERMS_CONVENTION]

        // The plugin contract's jar carries the same terms (DistributionTermsScriptSpec reads them
        // from the jar), so they come from this convention: no other script names them.
        and: 'the boot jar\'s module applies it'
        applies('bootstrap/build.gradle', 'distribution-terms-conventions')
    }

    // FR8 of add-functional-api-gate-test: the gate is verified by running it, so no test reads the
    //     convention's text — rewording or restructuring the script cannot fail one
    // Scenario: build-system/build-conventions / Refactoring the convention text alone breaks no test
    def "no test source reads the text of an api-gate convention script"() {
        given:
        def tests = testTrees()

        expect: 'the scan reached the test trees, among them the suite that runs the gate'
        tests.size() >= RepoSourceTree.KNOWN_TEST_SOURCES
        tests.any {
            it.text.contains("id 'api-compatibility-gate-conventions'")
        }

        and: 'no test names a gate script as a file'
        tests.findAll { file ->
            def code = RepoSourceTree.code(file)
            GATE_SCRIPTS.any { code.contains(it) }
        }.collect { RepoSourceTree.relative(it) } == []
    }

    /** A mis-resolved repoRoot would make the scans pass over an empty set. */
    private static final int KNOWN_SCRIPTS = 30

    private static boolean applies(String script, String pluginId) {
        RepoSourceTree.code(RepoSourceTree.repoRoot().resolve(script).toFile()).contains("id '${pluginId}'")
    }

    /** Every Gradle script of the repository, build outputs and Gradle's own state skipped. */
    private static List<File> gradleScripts() {
        files { it.toString().endsWith('.gradle') }
    }

    /** Every Groovy and Java source of a test tree: module tests, functional tests, shared fixtures. */
    private static List<File> testTrees() {
        files { path ->
            def relative = path.toString()
            (relative.endsWith('.groovy') || relative.endsWith('.java')) &&
                    (relative.contains('/src/test/') || relative.contains('/src/functionalTest/') ||
                    relative.startsWith('test-fixtures/src/'))
        }
    }

    private static List<File> files(Closure<Boolean> matching) {
        def root = RepoSourceTree.repoRoot()
        Files.walk(root).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) }
            .map { root.relativize(it) }
            .filter { Path relative ->
                !relative.iterator().toList()*.toString().any {
                    it in [
                        'build',
                        '.gradle',
                        'node_modules'
                    ]
                }
            }
            .filter { matching(it) }
            .map { root.resolve(it).toFile() }
            .toList()
        }
    }
}
