package com.github.oinsio.gnomish

import com.github.oinsio.gnomish.app.OperatorHomeFixture
import com.github.oinsio.gnomish.app.project.FactoryHome
import com.github.oinsio.gnomish.e2e.E2eProcessHarness
import com.github.oinsio.gnomish.testsupport.TestTaskProperty
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FactoryApplication bootstrap at context level (design D10): the real Spring
 * context boots, the typed properties bean is
 * populated, and the runtime is a headless outbound-only worker. FR2's
 * headless guarantee is proven at the strongest layer available to a unit
 * gate: with spring-boot-starter only, the classpath is the headless set, so
 * the application itself is the only party that can initiate a network
 * exchange. Process-level exit-code verification is deliberately out of
 * unit-gate scope (design D10).
 * Implements FR2, FR3 of add-project-skeleton.
 *
 * <p>NFR-R1, UX1 of collapse-composition-roots: this context-start spec, unedited by that change,
 * is the witness that the collapsed composition roots still assemble a context that boots the same
 * commands; the rest of the suite passing unedited carries the behavior-preservation half. The
 * bean inventory itself (NFR-R2) is pinned by {@code ApplicationBeanInventorySpec}.
 *
 * <p>Design D8 of add-project-registry: the context boots through {@link FactoryBoot} — the
 * {@code CommandExit} argument registration the configuration loader reads — against a factory
 * home of the spec's own.
 *
 * <p>FR6 of add-release-pipeline (design D3): {@code main} answers a sole {@code --version} before
 * the context starts — shown on the packaged jar, since only a real process can show that nothing
 * after the answer ran.
 */
class FactoryApplicationSpec extends Specification {

    @Shared
    @TempDir
    Path tmp

    @Shared
    OperatorHomeFixture operatorHome

    @Shared
    ConfigurableApplicationContext context

    @Shared
    FactoryProperties factoryProperties

    def setupSpec() {
        operatorHome = OperatorHomeFixture.install(tmp.resolve('home'))
        context = FactoryBoot.boot()
        factoryProperties = context.getBean(FactoryProperties)
    }

    def cleanupSpec() {
        context?.close()
        operatorHome?.close()
    }

    // FR2: clean boot — the Spring context initializes without errors
    def "spring context boots without errors"() {
        expect: 'the context is initialized and injected'
        context != null
    }

    // FR3: valid configuration binds; FR6 (design D5) of add-project-registry: the built-in default
    // lives on the record, not in a bundled application.yaml
    def "factory properties bean is populated with the record's built-in defaults"() {
        expect: 'the instance name is the record default, with no bundled factory block behind it'
        factoryProperties.instanceName() == 'default'
    }

    // FR2: headless runtime — the booted context is a plain annotation-config context
    def "the booted context is a plain annotation-config context"() {
        expect: 'the headless default context type was chosen'
        context instanceof AnnotationConfigApplicationContext
    }

    // FR2: outbound-only runtime — the *shipped* app stays the headless
    // spring-boot-starter set. Checked against the bootJar's bundled
    // BOOT-INF/lib/ (design D10's e2e.jarPath convention), not the test JVM's
    // own classloader: test-only dependencies that legitimately embed a Jetty
    // server for in-JVM HTTP stubbing (e.g. WireMock, task 4.4 of
    // add-tracker-port) put jetty/servlet jars on testRuntimeClasspath without
    // that ever reaching the packaged application.
    def "bootJar stays headless: no servlet/web-server jar is bundled"() {
        given: 'the packaged application jar built by this test run (dependsOn bootJar)'
        def jarPath = TestTaskProperty.required('e2e.jarPath')

        when: 'the bundled library jars are listed'
        def bundledLibNames = new ZipFile(jarPath).withCloseable { zip ->
            zip.entries().findAll {
                it.name.startsWith('BOOT-INF/lib/') && it.name.endsWith('.jar')
            }
            .collect { it.name }
        }

        then: 'no jetty or servlet-API jar is bundled — the app carries no HTTP server capability'
        bundledLibNames.every { !(it =~ /(?i)(jetty|servlet)/) }
    }

    // FR6 of add-release-pipeline (design D3): --version is answered before Spring starts — no
    // project is resolved, no configuration is loaded, no log file is opened
    def "FR6: the packaged jar prints its version for --version outside any project and exits 0"() {
        given: 'an empty factory home and a working directory that is no registered clone'
        def home = Files.createDirectories(tmp.resolve('version-home'))
        def elsewhere = Files.createDirectories(tmp.resolve('not-a-clone'))
        def expected = TestTaskProperty.required('e2e.productVersion')

        when:
        def result = new E2eProcessHarness().execute('--version', elsewhere, [], [], false,
        [(FactoryHome.HOME_VARIABLE): home.toString()])

        then: 'the version on one line, and success'
        result.exitCode() == 0
        result.stdout() == expected + '\n'
        result.stderr().isEmpty()

        and: 'the home stayed empty: no log file, no registry'
        Files.list(home).withCloseable { it.toList() } == []
    }
}
