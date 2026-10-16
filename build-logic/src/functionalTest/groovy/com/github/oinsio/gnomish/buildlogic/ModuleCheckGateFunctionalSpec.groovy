package com.github.oinsio.gnomish.buildlogic

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of one module's {@code check} (FR11 of split-into-modules; FR5, FR6 of
 * add-project-skeleton): two modules apply the real {@code library-conventions}, and
 * {@code :mod-a:check} is run once, with the whole tree in scope ({@code -PpitScope=all}), in the
 * miniature git repository of {@link MutationScopeConventionsFunctionalSpec}. The features read that
 * one run: what ran, which reports exist, and what PIT's own {@code mutations.xml} says it mutated.
 *
 * <p>{@code FooSpec} sits in {@code Foo}'s package on purpose: a target glob that reached the test
 * output would match it, so "only Java production classes are mutated" is not true by accident of
 * the package tree.
 */
class ModuleCheckGateFunctionalSpec extends Specification {

    static final String PKG = 'com/github/oinsio/gnomish/mini'

    @Shared
    @TempDir
    Path projectDir
    @Shared
    @TempDir
    Path home
    @Shared
    BuildResult result

    def setupSpec() {
        def repo = new MiniScopeRepository(projectDir, home)
        def catalog = Path.of(GradleRunnerSupport.requiredProperty('gnomish.versionCatalog'))
        repo.write('settings.gradle', """\
dependencyResolutionManagement {
    versionCatalogs {
        libs {
            from(files('${GradleRunnerSupport.quotedPath(catalog.toString())}'))
        }
    }
}
rootProject.name = 'mini-module-check'
includeBuild '${GradleRunnerSupport.quotedPath(GradleRunnerSupport.requiredProperty('gnomish.buildChecksDir'))}'
include 'mod-a', 'mod-b'
""")
        repo.write('build.gradle', "tasks.register('pitestAll')\n")
        repo.write('gradle/spotless-greclipse.properties', catalog.resolveSibling('spotless-greclipse.properties').text)
        repo.writeTestConventionInputs()
        ['mod-a': 'Foo', 'mod-b': 'Bar'].each { module, name ->
            repo.write("${module}/build.gradle", "plugins {\n    id 'library-conventions'\n}\n")
            repo.write("${module}/src/main/java/${PKG}/${name}.java", javaClass(name))
            repo.write("${module}/src/test/groovy/${PKG}/${name}Spec.groovy", spec(name))
        }
        repo.init([:])
        result = repo.runner(':mod-a:check', '-PpitScope=all').build()
    }

    // Scenario: build-system/quality-gates / A single module's check is self-contained
    def "FR11: one module's check runs its tests, its coverage and its mutation gate, and nothing of the other module"() {
        expect: 'the module\'s own test, coverage and mutation tasks ran'
        [':mod-a:test', ':mod-a:jacocoTestReport', ':mod-a:pitest', ':mod-a:pitestVerifyAllKilled'].every {
            result.task(it)?.outcome == SUCCESS
        }

        and: 'the other module was neither tested nor mutated'
        result.task(':mod-b:test') == null
        result.task(':mod-b:pitest') == null
        !Files.exists(projectDir.resolve('mod-b/build/reports/pitest'))

        and: 'what PIT mutated is this module\'s class alone'
        mutatedClasses() == ['com.github.oinsio.gnomish.mini.Foo'] as Set
    }

    // Scenario: build-system/quality-gates / Only Java production code is mutated
    def "FR6: PIT's targets are the Java production classes — the Groovy spec beside them in the same package is not mutated"() {
        expect:
        mutatedClasses() == ['com.github.oinsio.gnomish.mini.Foo'] as Set
        !mutatedClasses().any { it.endsWith('Spec') }
    }

    // Scenario: build-system/quality-gates / Reports generated
    def "FR5: the test task leaves JaCoCo XML and HTML reports under the build directory"() {
        expect:
        Files.isRegularFile(projectDir.resolve('mod-a/build/reports/jacoco/test/jacocoTestReport.xml'))
        Files.isRegularFile(projectDir.resolve('mod-a/build/reports/jacoco/test/html/index.html'))
        projectDir.resolve('mod-a/build/reports/jacoco/test/jacocoTestReport.xml').text.contains('name="com/github/oinsio/gnomish/mini/Foo"')
    }

    private Set<String> mutatedClasses() {
        def report = projectDir.resolve('mod-a/build/reports/pitest/mutations.xml')
        assert Files.isRegularFile(report): 'the module check wrote no PIT report'
        (report.text =~ /<mutatedClass>([^<]+)<\/mutatedClass>/).collect { it[1] } as Set
    }

    private static String javaClass(String name) {
        """\
package com.github.oinsio.gnomish.mini;

public final class ${name} {
    private ${name}() {}

    public static int twice(int x) {
        return x * 2;
    }
}
"""
    }

    private static String spec(String name) {
        """\
package com.github.oinsio.gnomish.mini

import spock.lang.Specification

class ${name}Spec extends Specification {
    def "twice doubles"() {
        expect:
        ${name}.twice(3) == 6
    }
}
"""
    }
}
