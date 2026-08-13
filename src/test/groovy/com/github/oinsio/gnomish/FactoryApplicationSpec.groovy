package com.github.oinsio.gnomish

import java.util.zip.ZipFile
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import spock.lang.Specification

/**
 * FactoryApplication bootstrap at context level (design D10): the real Spring
 * context boots with {@code application.yaml}, the typed properties bean is
 * populated, and the runtime is a headless outbound-only worker. FR2's
 * headless guarantee is proven at the strongest layer available to a unit
 * gate: with spring-boot-starter only, the classpath is the headless set, so
 * the application itself is the only party that can initiate a network
 * exchange. Process-level exit-code verification is deliberately out of
 * unit-gate scope (design D10).
 * Implements FR2, FR3 of add-project-skeleton.
 */
@SpringBootTest(classes = FactoryApplication)
class FactoryApplicationSpec extends Specification {

    @Autowired
    ApplicationContext context

    @Autowired
    FactoryProperties factoryProperties

    // FR2: clean boot — the Spring context initializes without errors
    def "spring context boots without errors"() {
        expect: 'the context is initialized and injected'
        context != null
    }

    // FR3: valid configuration binds — the bean carries the application.yaml value
    def "factory properties bean is populated from application.yaml"() {
        expect: 'the instance name equals the value declared in application.yaml'
        factoryProperties.instanceName() == 'gnomish-factory'
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
        def jarPath = System.getProperty('e2e.jarPath')
        assert jarPath != null: 'e2e.jarPath system property is not set (see tasks.named("test") in build.gradle)'

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
}
