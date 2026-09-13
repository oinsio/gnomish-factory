package com.github.oinsio.gnomish.build

import java.nio.file.Path
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Behavioral verification of {@code TestTimeInjectionCheck} — the gate is RUN over test sources
 * written per scenario, rather than checked by reading its regex.
 *
 * <p>The gate's defining question is "does this test source wire production real time?", and a
 * {@code system()} factory answers it whether or not it takes arguments: {@code
 * RemoteOutageGate.system(baseRefGit, cloneDir, idleInterval)} wires a real {@code SystemClock}
 * exactly as {@code GitInfrastructureRetry.system()} does. The zero-arity-only pattern the check
 * shipped with let the whole argument-carrying class of factories through unseen.
 *
 * <p>Hermetic: the mini project registers the task type straight off TestKit's plugin classpath —
 * no convention plugin is applied, so nothing is resolved and every run is {@code --offline}.
 */
class TestTimeInjectionCheckFunctionalSpec extends Specification {

    /** The check's own marker, spelled out rather than imported: this suite compiles without the
     * plugin's main output on its classpath, and a drifting literal fails the excuse scenario below. */
    private static final String MARKER = 'real-time-wiring:'

    @TempDir
    Path projectDir

    def setup() {
        write('settings.gradle', "rootProject.name = 'mini-time'\n")
        write('build.gradle', """\
// TestKit's injected classpath is a plugin-resolution classpath: no plugin of this build is
// applied (nothing to resolve, so every run stays offline), so the task type is put on the
// build script's own classpath instead.
buildscript {
    dependencies {
        classpath files(${pluginClasspath().collect { "'" + it + "'" }.join(', ')})
    }
}

import com.github.oinsio.gnomish.build.TestTimeInjectionCheck

tasks.register('checkTestTimeInjection', TestTimeInjectionCheck) {
    testSources.from(fileTree('src/test') { include '**/*.groovy' })
    report = layout.buildDirectory.file('reports/test-time-injection.txt')
}
""")
    }

    def "an argument-carrying system() factory fails the gate"() {
        given: 'a spec building a production-wired component through a factory that takes arguments'
        writeSpec('def gate = RemoteOutageGate.system(BaseRefGit.UNWIRED, dir, Duration.ofSeconds(30))')

        when:
        BuildResult failed = buildAndFail()

        then: 'the gate names the call rather than passing it'
        failed.task(':checkTestTimeInjection').outcome.name() == 'FAILED'
        failed.output.contains('RemoteOutageGate.system(')
    }

    def "a multi-line argument-carrying call fails on its own first line"() {
        given: 'the arguments wrap, as they do in an assembly spec'
        writeSpec('''def gate = RemoteOutageGate.system(
                BaseRefGit.UNWIRED, dir, Duration.ofSeconds(30))''')

        expect:
        buildAndFail().output.contains('RemoteOutageGate.system(')
    }

    def "a no-argument system() factory still fails the gate"() {
        given:
        writeSpec('def retry = GitInfrastructureRetry.system()')

        expect:
        buildAndFail().output.contains('GitInfrastructureRetry.system()')
    }

    def "the marker excuses an argument-carrying call, on its line and above it"() {
        given: 'both excusing shapes the check documents'
        writeSpec("""// ${MARKER} the gate is inert here — nothing drives its clock.
        def above = RemoteOutageGate.system(BaseRefGit.UNWIRED, dir, Duration.ofSeconds(30))
        def inline = RemoteOutageGate.system(BaseRefGit.UNWIRED, dir, Duration.ofSeconds(30)) // ${MARKER} same""")

        expect:
        build().task(':checkTestTimeInjection').outcome.name() == 'SUCCESS'
    }

    def "a call that merely starts with 'system' is not a violation"() {
        given: 'the JDK factories a spec legitimately uses'
        writeSpec('''def clock = Clock.systemUTC()
        def zone = Clock.systemDefaultZone()''')

        expect:
        build().task(':checkTestTimeInjection').outcome.name() == 'SUCCESS'
    }

    private void writeSpec(String body) {
        write('src/test/groovy/com/example/MiniSpec.groovy', """\
package com.example

class MiniSpec {
    def run() {
        ${body}
    }
}
""")
    }

    private BuildResult build() {
        runner().build()
    }

    private BuildResult buildAndFail() {
        runner().buildAndFail()
    }

    private GradleRunner runner() {
        GradleRunnerSupport.runner(projectDir, 'checkTestTimeInjection')
    }

    /** The build-logic classes TestKit publishes for the build under test. */
    private static List<String> pluginClasspath() {
        Properties metadata = new Properties()
        TestTimeInjectionCheckFunctionalSpec.classLoader
                .getResourceAsStream('plugin-under-test-metadata.properties')
                .withCloseable { metadata.load(it) }
        metadata.getProperty('implementation-classpath').split(File.pathSeparator).toList()
    }

    private void write(String relativePath, String content) {
        GradleRunnerSupport.writeFile(projectDir, relativePath, content)
    }
}
