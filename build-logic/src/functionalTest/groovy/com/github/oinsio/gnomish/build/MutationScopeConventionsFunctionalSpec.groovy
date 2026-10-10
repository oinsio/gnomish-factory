package com.github.oinsio.gnomish.build

import static org.gradle.testkit.runner.TaskOutcome.FAILED
import static org.gradle.testkit.runner.TaskOutcome.SKIPPED
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of the scope's CONSUMER — {@code pitest-scope-conventions} with
 * {@code pitest-gate-conventions} (D1, D6, NFR-R3, NFR-O1, M3 of scope-pit-locally). {@code
 * MutationScopeFunctionalSpec} pins what {@code MutationScopeSource} returns; this suite pins what
 * the build does with it: the targets PIT really mutates, the one skip the verdict shares, and the
 * one {@code PIT scope:} line per build.
 *
 * <p>The miniature repository holds one module applying the real {@code library-conventions}
 * (hence the real {@code pitest-conventions}) over the repository's own version catalog and the
 * real {@code build-checks}, and every mutation run is a real PIT run, offline against the outer
 * build's Gradle user home. {@code Foo} has a killing spec; {@code Bar} is in {@code
 * excludedClasses}.
 */
class MutationScopeConventionsFunctionalSpec extends Specification {

    static final String PKG = 'mod-a/src/main/java/com/github/oinsio/gnomish/mini'
    static final String FOO = "${PKG}/Foo.java"
    static final String BAR = "${PKG}/Bar.java"
    static final String SPEC = 'mod-a/src/test/groovy/com/github/oinsio/gnomish/mini/FooSpec.groovy'
    static final String KILLING = 'expect: Foo.twice(3) == 6'
    static final String STALE_REPORT = 'mod-a/build/reports/pitest/mutations.xml'
    static final String GATE = ':mod-a:pitestVerifyAllKilled'

    @TempDir
    Path projectDir
    @TempDir
    Path home

    MiniScopeRepository repo

    def setup() {
        repo = new MiniScopeRepository(projectDir, home)
        repo.write('settings.gradle', """\
dependencyResolutionManagement {
    versionCatalogs {
        libs {
            from(files('${GradleRunnerSupport.quotedPath(GradleRunnerSupport.requiredProperty('gnomish.versionCatalog'))}'))
        }
    }
}
rootProject.name = 'mini-scope-consumer'
includeBuild '${GradleRunnerSupport.quotedPath(GradleRunnerSupport.requiredProperty('gnomish.buildChecksDir'))}'
include 'mod-a'
""")
        repo.write('build.gradle', "tasks.register('pitestAll')\n")
        repo.write('mod-a/build.gradle', """\
plugins {
    id 'library-conventions'
}
pitest {
    excludedClasses = ['com.github.oinsio.gnomish.mini.Bar']
}
""")
        // `adversarial-gitconfig-conventions` declares this file as a test input; nothing reads it here.
        repo.write('test-fixtures/src/main/resources/adversarial-gitconfig', '# inert\n')
        // `stand-in-conventions` declares the stand-in library as a test input; nothing reads it here.
        repo.write('test-fixtures/src/main/resources/stand-in/stand-in.sh', '# inert\n')
        repo.write(FOO, javaClass('Foo', 'public static int twice(int x) { return x * 2; }'))
        repo.write(BAR, javaClass('Bar', 'public static int one() { return 1; }'))
        repo.write(SPEC, spec(KILLING))
        // A second commit on main: a root commit alone is no scope base (FR2), it would fall back.
        repo.init([:]).write('README', 'mini\n').commit('second commit on main')
        repo.git('checkout', '-q', '-b', 'feat')
    }

    def "NFR-R3, NFR-O1: a clean branch skips the module as a whole, a stale report is not judged, the line still prints"() {
        given: 'an earlier run left a report with a survivor'
        repo.write(STALE_REPORT, '<mutations><mutation status="SURVIVED"/></mutations>')

        when:
        BuildResult result = run(GATE)

        then:
        result.task(':mod-a:pitest').outcome == SKIPPED
        result.task(GATE).outcome == SKIPPED
        scopeLines(result) == ["PIT scope: branch — base ${base()} (merge-base main) — every module skipped (1)"]
    }

    def "NFR-O1, NFR-R2: a configuration-cache hit still announces the scope exactly once"() {
        when:
        run(GATE, '--configuration-cache')
        BuildResult second = run(GATE, '--configuration-cache')

        then:
        second.output.contains('Reusing configuration cache.')
        scopeLines(second).size() == 1
    }

    def "D1: a change to only an excluded class leaves nothing to mutate, so PIT is skipped, not failed"() {
        given:
        repo.write(BAR, javaClass('Bar', 'public static int one() { return 2 - 1; }'))

        when:
        BuildResult result = run(GATE)

        then:
        result.task(':mod-a:pitest').outcome == SKIPPED
        !result.output.contains('No mutations found')
    }

    def "D1, NFR-O1: a changed class is mutated and the line counts it"() {
        given:
        repo.write(FOO, javaClass('Foo', 'public static int twice(int x) { return x + x; }'))

        when:
        BuildResult result = run(GATE)

        then:
        result.task(':mod-a:pitest').outcome == SUCCESS
        result.task(GATE).outcome == SUCCESS
        scopeLines(result) == ["PIT scope: branch — base ${base()} (merge-base main) — mod-a: 1 class — 0 modules skipped"]
    }

    def "M3, FR3: deleting an assertion from a killing spec widens the module, and the gate fails on the survivor"() {
        given: 'the only production-relevant edit is the weakened spec'
        repo.write(SPEC, spec('when: Foo.twice(3)\n        then: noExceptionThrown()'))

        when:
        BuildResult result = repo.runner(GATE).buildAndFail()

        then:
        scopeLines(result) == ["PIT scope: branch — base ${base()} (merge-base main) — mod-a: whole module (test change) — 0 modules skipped"]
        result.task(':mod-a:pitest').outcome == FAILED
        result.output.contains('below threshold')
    }

    def "NFR-R1, NFR-O1: with no resolvable base the whole tree is mutated and the line says why"() {
        given:
        repo.git('branch', '-q', '-m', 'main', 'trunk')

        when:
        BuildResult result = run(GATE)

        then:
        result.task(':mod-a:pitest').outcome == SUCCESS
        scopeLines(result).size() == 1
        scopeLines(result)[0].startsWith('PIT scope: all — whole tree (no scope base:')
        scopeLines(result)[0].endsWith('— 1 modules mutated — 0 modules skipped')
    }

    def "D5: pitestAll requested by an abbreviation fails before any module runs, rather than mutating the branch scope"() {
        when:
        BuildResult result = repo.runner('pitestA').buildAndFail()

        then:
        result.output.contains('request pitestAll by its full name')
        result.task(':mod-a:pitest') == null
    }

    def "UX2, NFR-O1: a one-module run announces only the modules it mutates, not the branch's other changes"() {
        given: 'a second module, and a change only in the first'
        repo.write('settings.gradle', repo.dir.resolve('settings.gradle').text + "include 'mod-b'\n")
        repo.write('mod-b/build.gradle', "plugins {\n    id 'library-conventions'\n}\n")
        repo.write(FOO, javaClass('Foo', 'public static int twice(int x) { return x + x; }'))

        when:
        BuildResult result = run(':mod-b:pitestVerifyAllKilled')

        then:
        result.task(':mod-a:pitest') == null
        scopeLines(result) == ["PIT scope: branch — base ${base()} (merge-base main) — every module skipped (1)"]
    }

    private BuildResult run(String... arguments) {
        repo.runner(arguments).build()
    }

    private String base() {
        repo.git('rev-parse', 'main').take(7)
    }

    private static List<String> scopeLines(BuildResult result) {
        result.output.readLines().findAll { it.startsWith('PIT scope:') }
    }

    private static String javaClass(String name, String method) {
        """\
package com.github.oinsio.gnomish.mini;

public final class ${name} {
    private ${name}() {}

    ${method}
}
"""
    }

    private static String spec(String body) {
        """\
package com.github.oinsio.gnomish.mini

import spock.lang.Specification

class FooSpec extends Specification {
    def "twice doubles"() {
        ${body}
    }
}
"""
    }
}
