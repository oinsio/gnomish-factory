package com.github.oinsio.gnomish.buildlogic

import groovy.xml.XmlSlurper
import org.gradle.testkit.runner.BuildResult

/**
 * What one module's PIT run really mutates (FR6, FR11 of split-into-modules): the module's own Java
 * production classes, and neither a sibling module's classes on its classpath nor the Groovy specs
 * compiled into its test output. Read from the mutation report PIT writes, on the miniature
 * repository of {@link MutationScopeConventionsSpecBase} grown by a second module {@code mod-b}
 * that {@code mod-a} depends on, whose package the whole-module target glob of {@code mod-a} would
 * match by name.
 */
class MutationTargetFunctionalSpec extends MutationScopeConventionsSpecBase {

    static final String BAZ = 'mod-b/src/main/java/com/github/oinsio/gnomish/mini/b/Baz.java'

    // Scenario: build-system/build-conventions / Single-module change mutates only that module
    // Scenario: build-system/build-conventions / Mutation targets exclude Groovy test bytecode
    def "FR11: a one-module run mutates that module's Java classes only — no sibling, no Groovy spec"() {
        given: 'a sibling module on mod-a\'s classpath, in a package below mod-a\'s'
        repo.write('settings.gradle', repo.dir.resolve('settings.gradle').text + "include 'mod-b'\n")
        repo.write('mod-b/build.gradle', "plugins {\n    id 'library-conventions'\n}\n")
        repo.write('mod-a/build.gradle', repo.dir.resolve('mod-a/build.gradle').text +
                "dependencies {\n    implementation project(':mod-b')\n}\n")
        repo.write(BAZ, javaClass('Baz', 'public static int three() { return 3; }')
                .replace('package com.github.oinsio.gnomish.mini;', 'package com.github.oinsio.gnomish.mini.b;'))

        when: 'the developer runs the one module\'s mutation step over its whole surface'
        BuildResult result = run(':mod-a:pitest', '-PpitScope=all')

        then: 'the sibling module is not mutated at all'
        result.task(':mod-b:pitest') == null

        and: 'the report names mod-a\'s mutable Java class only: no sibling class, no Groovy spec'
        mutatedClasses() == ['com.github.oinsio.gnomish.mini.Foo'] as Set
    }

    private Set<String> mutatedClasses() {
        def report = new XmlSlurper().parse(repo.dir.resolve(STALE_REPORT).toFile())
        report.mutation*.mutatedClass*.text() as Set
    }
}
