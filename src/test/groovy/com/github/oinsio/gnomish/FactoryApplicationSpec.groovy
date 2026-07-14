package com.github.oinsio.gnomish

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
        expect: 'the instance id equals the value declared in application.yaml'
        factoryProperties.instanceId() == 'gnomish-local'
    }

    // FR2: headless runtime — the booted context is a plain annotation-config context
    def "the booted context is a plain annotation-config context"() {
        expect: 'the headless default context type was chosen'
        context instanceof AnnotationConfigApplicationContext
    }

    // FR2: outbound-only runtime — the classpath stays the headless spring-boot-starter
    // set; verified at the root, by the server-capability class being unresolvable
    def "classpath stays headless: server class #webStackClass is not resolvable"() {
        when: 'the class that any HTTP server support would require is looked up'
        Class.forName(webStackClass)

        then: 'it does not exist — the classpath is the headless spring-boot-starter set'
        thrown(ClassNotFoundException)

        where:
        webStackClass << [
            'org.springframework.web.context.WebApplicationContext',
            'jakarta.servlet.Servlet',
        ]
    }
}
