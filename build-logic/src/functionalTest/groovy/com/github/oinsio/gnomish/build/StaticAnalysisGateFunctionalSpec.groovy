package com.github.oinsio.gnomish.build

import static org.gradle.testkit.runner.TaskOutcome.FAILED
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of the format and static-analysis gates {@code java-conventions} puts on
 * every module (FR8, FR9 of add-project-skeleton, design D7, D8): a module that applies the
 * convention and configures nothing fails {@code check} on a misformatted file, and fails
 * {@code compileJava} on a NullAway violation and on dead private code.
 *
 * <p>The miniature project is the one {@link ParameterCountGateFunctionalSpec} builds: the real
 * convention over the repository's own catalog and the real {@code build-checks}, every run
 * {@code --offline}. The Spotless Groovy step reads its formatter preferences from the root
 * project, so the repository's own file is copied beside the mini catalog.
 */
class StaticAnalysisGateFunctionalSpec extends Specification {

    static final String SOURCE = 'src/main/java/com/github/oinsio/gnomish/mini/Mini.java'

    @TempDir
    Path projectDir

    def setup() {
        write('settings.gradle', """\
dependencyResolutionManagement {
    versionCatalogs {
        libs {
            from(files('${GradleRunnerSupport.quotedPath(catalog().toString())}'))
        }
    }
}
rootProject.name = 'mini-static-analysis'
includeBuild '${GradleRunnerSupport.quotedPath(GradleRunnerSupport.requiredProperty('gnomish.buildChecksDir'))}'
""")
        write('build.gradle', "plugins {\n    id 'java-conventions'\n}\n")
        write('gradle/spotless-greclipse.properties', catalog().resolveSibling('spotless-greclipse.properties').text)
    }

    // Scenario: build/quality-gates / Misformatted code fails the gate
    def "FR8: a misformatted file fails check naming the file, and spotlessApply makes check pass"() {
        given: 'a compilable class the formatter would lay out differently'
        write(SOURCE, 'package com.github.oinsio.gnomish.mini;\npublic final class Mini {  private Mini( ){}\n' +
                '    public static int twice(int x){return x*2;}  }\n')

        when:
        BuildResult failed = GradleRunnerSupport.runner(projectDir, 'check').buildAndFail()

        then: 'the format gate, not compilation, is what fails, and it names the file'
        failed.task(':spotlessJavaCheck').outcome == FAILED
        failed.output.contains('Mini.java')

        when: 'the developer runs the fix the gate points to'
        GradleRunnerSupport.runner(projectDir, 'spotlessApply').build()
        BuildResult fixed = GradleRunnerSupport.runner(projectDir, 'check').build()

        then:
        fixed.task(':spotlessJavaCheck').outcome == SUCCESS
        fixed.task(':check').outcome == SUCCESS
    }

    // Scenario: build/quality-gates / Null-safety violation fails compilation
    def "FR9: dereferencing a nullable value fails compileJava with the NullAway check named"() {
        given:
        write(SOURCE, javaClass('''\
import org.jspecify.annotations.Nullable;

public final class Mini {
    private Mini() {}

    public static int length(@Nullable String text) {
        return text.length();
    }
}
'''))

        when:
        BuildResult failed = GradleRunnerSupport.runner(projectDir, 'compileJava').buildAndFail()

        then:
        failed.task(':compileJava').outcome == FAILED
        failed.output.contains('[NullAway]')
        failed.output.contains("dereferenced expression 'text' is @Nullable")
    }

    // Scenario: build/quality-gates / Dead private code fails compilation
    def "FR9: an unused private #kind fails compileJava with #check named"() {
        given:
        write(SOURCE, javaClass("""\
public final class Mini {
    private Mini() {}

    ${member}

    public static int twice(int x) {
        return x * 2;
    }
}
"""))

        when:
        BuildResult failed = GradleRunnerSupport.runner(projectDir, 'compileJava').buildAndFail()

        then:
        failed.task(':compileJava').outcome == FAILED
        failed.output.contains("[${check}]")

        // F111: Error Prone exempts by design every member whose name begins with "unused" (any
        // case), so a fixture named that way compiles clean and proves nothing; the names here
        // are ordinary ones, as dead code in production would carry.
        where:
        kind       | member                                          || check
        'method'   | 'private static int helper() { return 1; }'     || 'UnusedMethod'
        'variable' | 'private static final int LIMIT = 1;'           || 'UnusedVariable'
    }

    // The contrast the three failures are read against: the same convention compiles clean code.
    def "FR9: a class with no violation compiles under the same convention"() {
        given:
        write(SOURCE, javaClass('''\
public final class Mini {
    private Mini() {}

    public static int twice(int x) {
        return x * 2;
    }
}
'''))

        expect:
        GradleRunnerSupport.runner(projectDir, 'compileJava').build().task(':compileJava').outcome == SUCCESS
    }

    private static String javaClass(String body) {
        "package com.github.oinsio.gnomish.mini;\n\n${body}"
    }

    private static Path catalog() {
        Path.of(GradleRunnerSupport.requiredProperty('gnomish.versionCatalog'))
    }

    private void write(String relativePath, String content) {
        GradleRunnerSupport.writeFile(projectDir, relativePath, content)
    }
}
