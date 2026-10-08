package com.github.oinsio.gnomish

import ch.qos.logback.classic.Logger
import ch.qos.logback.core.FileAppender
import com.github.oinsio.gnomish.app.OperatorHomeFixture
import com.github.oinsio.gnomish.e2e.E2eProcessHarness
import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory
import org.springframework.context.ConfigurableApplicationContext
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The test suite does not write to the operator's log (task 2.3 of harden-logging-observability,
 * FR11, M4). The defect this closes was observed live: `logback-spring.xml` sent everything to the
 * operator's own log file under `~/.gnomish`, every {@code @SpringBootTest} in this module boots a real context
 * that applies it, and Gradle test-worker stack traces — provoked on purpose by specs asserting
 * failure paths — landed in the file an operator later greps as evidence of what their factory did.
 *
 * <p>Two halves, because the suite reaches the production configuration two ways:
 *
 * <ul>
 *   <li><b>In this JVM</b>, `logback-test.xml` on the test classpath takes precedence over
 *       `logback-spring.xml` for Logback and for Spring Boot alike, so a booted context routes to
 *       the build directory. Asserted by booting one and following where a line actually goes.
 *   <li><b>Out of process</b>, {@code E2eProcessHarness} spawns the packaged jar, which carries the
 *       production configuration by design — a test-classpath file cannot reach it. That
 *       configuration writes where the operator configuration loader decides, under the factory
 *       home (FR11 of add-project-registry), and the harness points {@code GNOMISH_HOME} at a
 *       temporary folder — which is what keeps it off the operator's file.
 * </ul>
 *
 * <p>The marker is emitted at INFO deliberately: INFO is the level that reaches the file appender
 * and not the WARN+ console, so a leak into the production configuration would show up here rather
 * than being masked by a threshold.
 *
 * <p>Implements FR11, M4 of harden-logging-observability; FR11 of add-project-registry.
 */
class OperatorLogIsolationSpec extends Specification {

    @Shared
    @TempDir
    Path operatorHomeDir

    @Shared
    OperatorHomeFixture operatorHome

    @Shared
    ConfigurableApplicationContext context

    /** The operator's own factory home, whose logs no spec may write. */
    private static final Path OPERATOR_HOME = Path.of(System.getProperty('user.home'), '.gnomish')

    // Design D8 of add-project-registry: booted through the CommandExit argument registration,
    // against a factory home of the spec's own.
    def setupSpec() {
        operatorHome = OperatorHomeFixture.install(operatorHomeDir.resolve('home'))
        context = FactoryBoot.boot()
    }

    def cleanupSpec() {
        context?.close()
        operatorHome?.close()
    }

    // FR11: a booted Spring context routes the suite's lines to the build directory
    def "a line logged from a booted context lands in the build directory, not the operator's log"() {
        given: 'a marker no other run can have written'
        String marker = "operator-log-isolation-${UUID.randomUUID()}"

        when:
        LoggerFactory.getLogger(OperatorLogIsolationSpec).info(marker)

        then: 'the test configuration caught it'
        carriesLine(testLogFile(), marker)

        and: 'and the operator\'s own log never saw it'
        !carriesLine(operatorLog(), marker)
    }

    // M4: no appender of the booted context points anywhere inside the operator's home factory dir.
    // The repository under test is exempt: a factory that builds itself checks this repository out
    // into a worktree under its own home, so the suite's build directory lives there too — and it
    // is the suite's own output, not a file of the operator's.
    def "no appender attached to the booted root logger writes under the operator's .gnomish directory"() {
        given:
        Path repository = RepoSourceTree.repoRoot().toAbsolutePath().normalize()

        expect:
        fileAppenders().every { FileAppender<?> appender ->
            Path file = Path.of(appender.file).toAbsolutePath().normalize()
            !file.startsWith(OPERATOR_HOME) || file.startsWith(repository)
        }
    }

    // M4, out-of-process half: the E2E layer's spawned production binary logs under a temporary
    // factory home — the production configuration writes the file the loader published from it
    // (FR11 of add-project-registry)
    def "the E2E harness points the spawned factory's home, and so its log, at a temporary directory"() {
        expect:
        !E2eProcessHarness.HOME.root().startsWith(OPERATOR_HOME)
        !E2eProcessHarness.HOME.hostLogFile().startsWith(OPERATOR_HOME)
    }

    private static Path testLogFile() {
        List<FileAppender<?>> appenders = fileAppenders()
        assert appenders.size() == 1: "expected exactly one file appender from logback-test.xml, got ${appenders*.name}"
        return Path.of(appenders.first().file)
    }

    private static List<FileAppender<?>> fileAppenders() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)
        def attached = []
        root.iteratorForAppenders().forEachRemaining { attached.add(it) }
        return attached.findAll {
            it instanceof FileAppender
        } as List<FileAppender<?>>
    }

    /** The operator's project-less log — the file a leaked line would reach first. */
    private static Path operatorLog() {
        return OPERATOR_HOME.resolve('logs').resolve('factory.log')
    }

    /**
     * Whether any line of this log carries the marker — streamed, never held whole. Both logs this
     * spec reads are append-only and unbounded: the build directory's file grows across every
     * local run until someone cleans it, and the operator's own log is the production file. Read
     * as one string, either can outgrow the heap, and the spec then fails with an {@code
     * OutOfMemoryError} that says nothing about log isolation (observed at 624 MB after four
     * consecutive local runs). A missing file is an empty log, which is the answer both
     * assertions want: nothing was written there.
     */
    private static boolean carriesLine(Path log, String marker) {
        if (!Files.isRegularFile(log)) {
            return false
        }
        Files.lines(log).withCloseable { lines ->
            lines.anyMatch { String line -> line.contains(marker) }
        }
    }
}
