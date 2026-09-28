package com.github.oinsio.gnomish.build

import org.gradle.testkit.runner.BuildResult
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Behavioral verification of the parameter-count gate's WIRING — NFR-R1, M2 of
 * add-parameter-count-gate (design D10). The check's semantics (the two exemptions, the
 * exemption annotation, the message) are pinned by its own unit spec on Error Prone's
 * compilation harness inside {@code build-checks}; what this suite proves is the one thing
 * that harness cannot: that {@code java-conventions} puts the check on a module's processor
 * path, so a module applying the convention fails {@code compileJava} on an eight-parameter
 * method with no configuration of its own (FR5).
 *
 * <p>The miniature project's settings {@code includeBuild} the real {@code build-checks}
 * directory (passed as a system property), so the check compiled under is the one the main
 * build ships. Every run is {@code --offline} against the outer build's Gradle user home.
 */
class ParameterCountGateFunctionalSpec extends Specification {

    @TempDir
    Path projectDir

    def setup() {
        write('settings.gradle', """\
// The same catalog the convention plugin reads in the main build, and the same included
// build that substitutes the gate's coordinates there (design D9).
dependencyResolutionManagement {
    versionCatalogs {
        libs {
            from(files('${escape(versionCatalog())}'))
        }
    }
}

rootProject.name = 'mini-gate'

includeBuild '${escape(buildChecksDir())}'
""")
        write('build.gradle', '''\
plugins {
    id 'java-conventions'
}
''')
    }

    def "FR5: a module applying the convention fails compileJava on an eight-parameter method"() {
        given: 'one method over the limit, in a module that declares nothing about the gate'
        writeSource(8)

        when:
        BuildResult failed = buildAndFail()

        then: 'compilation — not resolution or configuration — is what fails'
        failed.task(':compileJava').outcome.name() == 'FAILED'

        and: 'the diagnostic is the project check, naming the count and the limit (NFR-O1)'
        failed.output.contains('[ParameterCountLimit]')
        failed.output.contains('8 parameters; the limit is 7')
    }

    def "FR5: a seven-parameter method compiles under the convention"() {
        given:
        writeSource(7)

        expect:
        build().task(':compileJava').outcome.name() == 'SUCCESS'
    }

    private void writeSource(int parameters) {
        String list = (1..parameters).collect { "int p${it}" }.join(', ')
        write('src/main/java/com/example/mini/Wide.java', """\
package com.example.mini;

final class Wide {
    private Wide() {}

    static int sum(${list}) {
        return ${(1..parameters).collect { "p${it}" }.join(' + ')};
    }
}
""")
    }

    private BuildResult build() {
        GradleRunnerSupport.runner(projectDir, 'compileJava').build()
    }

    private BuildResult buildAndFail() {
        GradleRunnerSupport.runner(projectDir, 'compileJava').buildAndFail()
    }

    private static String buildChecksDir() {
        property('gnomish.buildChecksDir')
    }

    private static String versionCatalog() {
        property('gnomish.versionCatalog')
    }

    private static String property(String name) {
        String value = System.getProperty(name)
        assert value != null: "functionalTest must pass -D${name} (see build-logic/build.gradle)"
        value
    }

    /** A path inside a single-quoted Groovy literal: backslashes and quotes escaped. */
    private static String escape(String path) {
        path.replace('\\', '\\\\').replace("'", "\\'")
    }

    private void write(String relativePath, String content) {
        GradleRunnerSupport.writeFile(projectDir, relativePath, content)
    }
}
