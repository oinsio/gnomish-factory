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
 * RemoteOutageGate.system(baseRefGit, cloneDir, idleInterval)} reads {@code InstantSource.system()}
 * exactly as {@code GitInfrastructureRetry.system()} does. The zero-arity-only pattern the check
 * shipped with let the whole argument-carrying class of factories through unseen.
 *
 * <p>The literal set widened with FR21 of supervise-daemon-loops-and-embed-dashboard: a system
 * clock, a direct {@code Instant.now()} and the real sleeper are real time just as a {@code
 * system()} factory is, so the gate names each of them too.
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

    // FR21 of supervise-daemon-loops-and-embed-dashboard: the gate bans the same real-time literal
    //     set as the production gate, not only the system() factories.
    def "a spec building real time fails the gate: #shape"() {
        given:
        writeSpec(body)

        expect:
        buildAndFail().output.contains(named)

        where:
        shape | body || named
        'the system clock' | 'def clock = Clock.systemUTC()' || 'Clock.systemUTC()'
        'the system zone clock' | 'def zone = Clock.systemDefaultZone()' || 'Clock.systemDefaultZone()'
        'the JDK instant source' | 'def source = InstantSource.system()' || 'InstantSource.system()'
        'a direct read' | 'def at = Instant.now()' || 'Instant.now()'
        'the real sleeper' | 'def sleeper = new ThreadSleeper()' || 'new ThreadSleeper()'
        'the deleted adapter' | 'def clock = new SystemClock()' || 'new SystemClock()'
    }

    def "a look-alike of a real-time read is not a violation"() {
        given: 'a declaration named now, a fixed clock, and a virtual source'
        writeSpec('''Instant now() { clock.instant() }
        def fixed = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
        def virtual = new VirtualClock()''')

        expect:
        build().task(':checkTestTimeInjection').outcome.name() == 'SUCCESS'
    }

    def "the marker excuses a real clock in a fixture that assembles the shipped composition"() {
        given:
        writeSpec("""// ${MARKER} the shipped composition's one time equipment; time is not the subject.
        def clock = InstantSource.system()""")

        expect:
        build().task(':checkTestTimeInjection').outcome.name() == 'SUCCESS'
    }

    // A marker left over from a call that moved would silently excuse the next real time written
    //     under it, so the gate reports it — as the parameter-count gate reports an exemption that
    //     would pass without itself.
    def "a marker that excuses nothing fails the gate: #shape"() {
        given:
        writeSpec(body)

        expect:
        buildAndFail().output.contains('marker excuses nothing')

        where:
        shape | body
        'above a call with no real time' | "// ${MARKER} stale.\n        def time = new ManualRunConfiguration().timeEquipment()"
        'trailing a call with no real time' | "def clock = new VirtualClock() // ${MARKER} stale"
        'above a blank line' | "// ${MARKER} stale.\n\n        def clock = new VirtualClock()"
    }

    def "a mention of the marker in prose is not a marker"() {
        given:
        writeSpec("""/**
         * Honours the in-place {@code ${MARKER}} justification.
         */
        def clock = new VirtualClock()""")

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
