package com.github.oinsio.gnomish.buildlogic

import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The miniature repository the mutation-scope consumer specs run real PIT against: one module,
 * {@code mod-a}, applying the real {@code library-conventions} over the repository's own version
 * catalog and the real {@code build-checks}, on a feature branch one commit past {@code main}.
 * {@code Foo} has a killing spec; {@code Bar} is in {@code excludedClasses}. Shared by
 * {@link MutationScopeConventionsFunctionalSpec} and {@link MutationTargetFunctionalSpec}.
 */
abstract class MutationScopeConventionsSpecBase extends Specification {

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
        repo.writeTestConventionInputs()
        repo.write(FOO, javaClass('Foo', 'public static int twice(int x) { return x * 2; }'))
        repo.write(BAR, javaClass('Bar', 'public static int one() { return 1; }'))
        repo.write(SPEC, spec(KILLING))
        // A second commit on main: a root commit alone is no scope base (FR2), it would fall back.
        repo.init([:]).write('README', 'mini\n').commit('second commit on main')
        repo.git('checkout', '-q', '-b', 'feat')
    }

    protected BuildResult run(String... arguments) {
        repo.runner(arguments).build()
    }

    protected String base() {
        repo.git('rev-parse', 'main').take(7)
    }

    protected static List<String> scopeLines(BuildResult result) {
        result.output.readLines().findAll { it.startsWith('PIT scope:') }
    }

    protected static String javaClass(String name, String method) {
        """\
package com.github.oinsio.gnomish.mini;

public final class ${name} {
    private ${name}() {}

    ${method}
}
"""
    }

    protected static String spec(String body) {
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
